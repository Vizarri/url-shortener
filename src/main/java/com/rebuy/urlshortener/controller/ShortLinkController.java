package com.rebuy.urlshortener.controller;

import com.rebuy.urlshortener.db.model.ShortLink;
import com.rebuy.urlshortener.dto.ShortLinkCreationResult;
import com.rebuy.urlshortener.dto.request.CreateShortLinkRequest;
import com.rebuy.urlshortener.dto.response.CreateShortLinkResponse;
import com.rebuy.urlshortener.service.ShortLinkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/** Creates short links and handles browser redirects for four-character Base62 codes. */
@RestController
@RequiredArgsConstructor
public class ShortLinkController {

    private final ShortLinkService shortLinkService;

    /**
     * Creates or reuses a link and builds its URL from the current application address.
     *
     * @param request validated JSON request containing the destination URL
     * @return HTTP 201 for a new link or 200 for an existing one, both with Location
     */
    @PostMapping("/api/links")
    public ResponseEntity<CreateShortLinkResponse> create(@Valid @RequestBody CreateShortLinkRequest request) {
        ShortLinkCreationResult result = shortLinkService.create(request.url());
        ShortLink link = result.link();
        URI shortUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .pathSegment(link.getCode())
                .build()
                .toUri();
        CreateShortLinkResponse response = new CreateShortLinkResponse(
                link.getCode(), shortUrl.toASCIIString(), link.getTargetUrl());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .location(shortUrl)
                .body(response);
    }

    /**
     * Redirects a short link to its stored destination.
     *
     * @param code case-sensitive public code extracted from the request path
     * @return HTTP 302 with the original URL in the Location header
     */
    @GetMapping("/{code:[a-zA-Z0-9]{4}}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(shortLinkService.getTargetUrl(code)))
                .build();
    }
}
