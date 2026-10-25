package com.rebuy.urlshortener.db.repository;

import com.rebuy.urlshortener.db.model.ShortLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Provides persistence access to short links using their internal IDs or public codes. */
public interface ShortLinkRepository extends JpaRepository<ShortLink, Long> {

    /**
     * Finds the link assigned to a case-sensitive public code.
     *
     * @param code public four-character Base62 code
     * @return the stored link, or an empty optional when the code is unknown
     */
    Optional<ShortLink> findByCode(String code);

    /**
     * Finds the mapping for an exact destination, including its query and fragment.
     *
     * @param targetUrl validated destination with surrounding whitespace removed
     * @return the existing mapping, or an empty optional when none exists
     */
    Optional<ShortLink> findByTargetUrl(String targetUrl);

    /**
     * Serializes creators of the same URL across application instances until commit or rollback.
     * The scalar SELECT avoids mapping PostgreSQL's void lock-function result through JPA.
     * Hash collisions only serialize unrelated URLs; lookup still compares the complete URL.
     *
     * @param targetUrl destination whose creation must be serialized
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:targetUrl, 0))", nativeQuery = true)
    void lockTargetUrl(@Param("targetUrl") String targetUrl);
}
