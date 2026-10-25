package com.rebuy.urlshortener.integration.db;

import com.rebuy.urlshortener.db.model.ShortLink;
import com.rebuy.urlshortener.db.repository.ShortLinkRepository;
import com.rebuy.urlshortener.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies JPA reads and PostgreSQL constraints independently of the HTTP creation API. */
class ShortLinkRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ShortLinkRepository repository;

    /** A persisted row retains its identifier, destination, and creation timestamp. */
    @Test
    void findsPersistedLinkByCode() {
        String targetUrl = "https://example.com/product/42";
        Long savedId = fixtures.insert("aB7x", targetUrl);

        ShortLink found = repository.findByCode("aB7x").orElseThrow();

        assertThat(found.getId()).isPositive().isEqualTo(savedId);
        assertThat(found.getCode()).isEqualTo("aB7x");
        assertThat(found.getTargetUrl()).isEqualTo(targetUrl);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    /** A duplicate public code must never overwrite the original row. */
    @Test
    void preservesOriginalLinkWhenDuplicateCodeIsRejected() {
        String originalUrl = "https://example.com/original";
        Long originalId = fixtures.insert("aB7x", originalUrl);

        assertThatThrownBy(() -> fixtures.insert("aB7x", "https://example.com/replacement"))
                .isInstanceOf(DataIntegrityViolationException.class);

        ShortLink original = repository.findByCode("aB7x").orElseThrow();
        assertThat(original.getId()).isEqualTo(originalId);
        assertThat(original.getTargetUrl()).isEqualTo(originalUrl);
        assertThat(repository.count()).isEqualTo(1);
    }

    /** The database enforces the four-character Base62 format for every insert path. */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "abcde", "ab-c", "ab_c", "abcé"})
    void rejectsMalformedCode(String code) {
        assertThatThrownBy(() -> fixtures.insert(code, "https://example.com/product/42"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.count()).isZero();
    }
}
