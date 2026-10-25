package com.rebuy.urlshortener.service;

import com.rebuy.urlshortener.db.model.ShortLink;
import com.rebuy.urlshortener.db.repository.ShortLinkRepository;
import com.rebuy.urlshortener.dto.ShortLinkCreationResult;
import com.rebuy.urlshortener.exception.ShortCodeCollisionException;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Encapsulates short-link repository access, transactions, and database error translation. */
@Service
@RequiredArgsConstructor
public class ShortLinkDbService {

    private static final String CODE_UNIQUE_CONSTRAINT = "uk_short_links_code";
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private final ShortLinkRepository shortLinkRepository;
    private final ShortCodeGenerator shortCodeGenerator;

    /**
     * Rechecks the destination after acquiring its database transaction lock, then
     * returns the existing mapping or generates a code and inserts a fresh entity.
     * Reused mappings do not invoke the generator, including concurrent requests.
     * READ_COMMITTED lets the lookup see a competing creator's commit after
     * waiting for its lock.
     * The proxy completes commit or rollback before returning to the coordinator.
     *
     * @param targetUrl validated destination URL
     * @return the committed mapping and whether this attempt created it
     * @throws ShortCodeCollisionException when the public code is already assigned
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ShortLinkCreationResult findOrCreate(String targetUrl) {
        shortLinkRepository.lockTargetUrl(targetUrl);
        Optional<ShortLink> existing = shortLinkRepository.findByTargetUrl(targetUrl);
        if (existing.isPresent()) {
            return new ShortLinkCreationResult(existing.get(), false);
        }

        ShortLink link = new ShortLink();
        link.setCode(shortCodeGenerator.generate());
        link.setTargetUrl(targetUrl);
        try {
            return new ShortLinkCreationResult(shortLinkRepository.saveAndFlush(link), true);
        } catch (DataIntegrityViolationException exception) {
            if (isCodeCollision(exception)) {
                throw new ShortCodeCollisionException(exception);
            }
            throw exception;
        }
    }

    /**
     * Reads a short link within a transaction without changing its stored values.
     *
     * @param code case-sensitive public short code
     * @return the stored link, or an empty optional when the code is unknown
     */
    @Transactional(readOnly = true)
    public Optional<ShortLink> findByCode(String code) {
        return shortLinkRepository.findByCode(code);
    }

    /**
     * Distinguishes a public-code collision from unrelated integrity failures.
     *
     * @param exception translated persistence failure
     * @return whether PostgreSQL rejected the candidate's named unique constraint
     */
    private boolean isCodeCollision(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && UNIQUE_VIOLATION_SQL_STATE.equals(violation.getSQLState())
                    && CODE_UNIQUE_CONSTRAINT.equals(violation.getConstraintName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
