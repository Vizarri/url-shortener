package com.rebuy.urlshortener.service;

import com.rebuy.urlshortener.db.model.ShortLink;
import com.rebuy.urlshortener.dto.ShortLinkCreationResult;
import com.rebuy.urlshortener.exception.InvalidUrlException;
import com.rebuy.urlshortener.exception.ShortCodeAllocationException;
import com.rebuy.urlshortener.exception.ShortCodeCollisionException;
import com.rebuy.urlshortener.exception.ShortLinkNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;

/** Coordinates link creation and resolves public codes to destination URLs. */
@Service
@RequiredArgsConstructor
public class ShortLinkService {

    private static final int MAX_CODE_ATTEMPTS = 10;
    private static final int MAX_ENCODED_URL_BYTES = 16_384;

    private final ShortLinkDbService shortLinkDbService;

    /**
     * Creates or reuses a link for the exact URL, retrying public-code collisions.
     * Existing transactions are rejected so each DB service insert completes its own
     * transaction before a retry, and a successful result has already been committed.
     *
     * @param url destination URL; surrounding whitespace is ignored
     * @return the committed mapping and whether it was created by this request
     * @throws InvalidUrlException when the destination is invalid or exceeds the encoded size limit
     * @throws ShortCodeAllocationException when all code candidates collide
     */
    @Transactional(propagation = Propagation.NEVER)
    public ShortLinkCreationResult create(String url) {
        String targetUrl = validateUrl(url);
        ShortCodeCollisionException lastCollision = null;

        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            try {
                return shortLinkDbService.findOrCreate(targetUrl);
            } catch (ShortCodeCollisionException exception) {
                lastCollision = exception;
            }
        }

        throw new ShortCodeAllocationException(lastCollision);
    }

    /**
     * Loads the original URL without changing its query parameters or fragment.
     *
     * @param code case-sensitive public short code
     * @return the destination URL stored for the code
     * @throws ShortLinkNotFoundException when no matching link exists
     */
    public String getTargetUrl(String code) {
        return shortLinkDbService.findByCode(code)
                .map(ShortLink::getTargetUrl)
                .orElseThrow(ShortLinkNotFoundException::new);
    }

    /**
     * Validates URI syntax and encoded redirect size without rewriting the destination.
     *
     * @param url user-supplied destination
     * @return the validated URL with surrounding whitespace removed
     */
    private String validateUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidUrlException();
        }

        String targetUrl = url.strip();
        validateUnicode(targetUrl);
        try {
            URI uri = new URI(targetUrl);
            boolean httpScheme = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            if (!httpScheme || uri.getHost() == null || uri.getPort() > 65535) {
                throw new InvalidUrlException();
            }
            // Location uses ASCII encoding, so its character count is also its byte size.
            if (uri.toASCIIString().length() > MAX_ENCODED_URL_BYTES) {
                throw new InvalidUrlException(
                        "URL must not exceed " + MAX_ENCODED_URL_BYTES + " bytes after URL encoding.");
            }
        } catch (URISyntaxException exception) {
            throw new InvalidUrlException();
        }
        return targetUrl;
    }

    /**
     * Rejects unpaired UTF-16 surrogates before URI percent-encoding.
     *
     * @param url destination whose Unicode characters must be well-formed
     * @throws InvalidUrlException when a surrogate does not belong to a valid pair
     */
    private void validateUnicode(String url) {
        for (int index = 0; index < url.length(); index++) {
            char character = url.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 == url.length() || !Character.isLowSurrogate(url.charAt(index + 1))) {
                    throw new InvalidUrlException();
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new InvalidUrlException();
            }
        }
    }
}
