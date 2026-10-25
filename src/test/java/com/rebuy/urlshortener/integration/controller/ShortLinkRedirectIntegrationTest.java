package com.rebuy.urlshortener.integration.controller;

import com.rebuy.urlshortener.db.repository.ShortLinkRepository;
import com.rebuy.urlshortener.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies redirect routing and exact destinations using independently seeded links. */
class ShortLinkRedirectIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ShortLinkRepository repository;

    /** Redirects preserve encoded query parameters and fragments. */
    @Test
    void redirectsToExactTargetIncludingQueryAndFragment() throws Exception {
        String targetUrl = "https://example.com/product/42?campaign=rebuy&query=phone%20case#details";
        fixtures.insert("aB7x", targetUrl);

        api.assertRedirect("aB7x", targetUrl);
    }

    /** A well-formed but unknown code is not redirected. */
    @Test
    void returnsNotFoundForUnknownCode() throws Exception {
        HttpResponse<String> response = api.get("/zzZZ");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    /** Malformed paths do not match the public short-link route. */
    @ParameterizedTest
    @ValueSource(strings = {"abc", "abcde", "ab-c", "ab_c"})
    void returnsNotFoundForMalformedCode(String code) throws Exception {
        HttpResponse<String> response = api.get("/" + code);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Location")).isEmpty();
    }

    /** Codes that differ only in letter case identify different destinations. */
    @Test
    void treatsCodesAsCaseSensitive() throws Exception {
        String lowercaseTarget = "https://example.com/lowercase";
        String uppercaseTarget = "https://example.com/uppercase";
        fixtures.insert("abcd", lowercaseTarget);
        fixtures.insert("Abcd", uppercaseTarget);

        api.assertRedirect("abcd", lowercaseTarget);
        api.assertRedirect("Abcd", uppercaseTarget);

        assertThat(repository.count()).isEqualTo(2);
    }
}
