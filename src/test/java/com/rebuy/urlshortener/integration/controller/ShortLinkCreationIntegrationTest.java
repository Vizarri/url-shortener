package com.rebuy.urlshortener.integration.controller;

import com.rebuy.urlshortener.db.model.ShortLink;
import com.rebuy.urlshortener.db.repository.ShortLinkRepository;
import com.rebuy.urlshortener.integration.BaseIntegrationTest;
import com.rebuy.urlshortener.support.ConcurrentRequests;
import com.rebuy.urlshortener.support.LinkApiClient.CreatedLink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies creation, validation, and committed destinations through the HTTP API. */
class ShortLinkCreationIntegrationTest extends BaseIntegrationTest {

    private static final int MAX_ENCODED_URL_LENGTH = 16_384;

    @Autowired
    private ShortLinkRepository shortLinkRepository;

    /** The returned short URL must resolve to the exact committed destination. */
    @Test
    void createsLinkAndRedirectsWithoutChangingQueryOrFragment() throws Exception {
        String targetUrl = "https://example.com/product/42?campaign=rebuy&query=phone%20case#details";

        HttpResponse<String> response = api.create(targetUrl);

        CreatedLink link = api.assertCreated(response, targetUrl);
        ShortLink saved = shortLinkRepository.findByCode(link.code()).orElseThrow();
        assertThat(saved.getTargetUrl()).isEqualTo(targetUrl);
        assertThat(saved.getId()).isPositive();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(shortLinkRepository.count()).isEqualTo(1);
        api.assertRedirect(link.code(), targetUrl);
    }

    /** A repeated destination returns the same public link and retains the original row. */
    @Test
    void reusesTheExistingLinkForTheSameDestination() throws Exception {
        String targetUrl = "https://example.com/product/42?campaign=rebuy#details";
        CreatedLink original = api.createLink(targetUrl);
        ShortLink originalRow = shortLinkRepository.findByCode(original.code()).orElseThrow();

        HttpResponse<String> response = api.create(targetUrl);

        CreatedLink reused = api.assertReused(response, targetUrl);
        assertThat(reused).isEqualTo(original);
        ShortLink stored = shortLinkRepository.findByCode(reused.code()).orElseThrow();
        assertThat(stored.getId()).isEqualTo(originalRow.getId());
        assertThat(stored.getCreatedAt()).isEqualTo(originalRow.getCreatedAt());
        assertThat(shortLinkRepository.count()).isEqualTo(1);
        api.assertRedirect(reused.code(), targetUrl);
    }

    /** Whitespace removed during validation does not allocate another mapping for the same URL. */
    @Test
    void reusesTheExistingLinkAfterTrimmingOuterWhitespace() throws Exception {
        String targetUrl = "https://example.com/product/42?source=rebuy#details";
        CreatedLink original = api.createLink(targetUrl);

        HttpResponse<String> response = api.create(" \t" + targetUrl + "\n ");

        CreatedLink reused = api.assertReused(response, targetUrl);
        assertThat(reused).isEqualTo(original);
        assertThat(shortLinkRepository.count()).isEqualTo(1);
    }

    /** Exact comparison preserves query values, parameter ordering, and fragment distinctions. */
    @Test
    void keepsDifferentQueriesAndFragmentsAsSeparateDestinations() throws Exception {
        List<String> targetUrls = List.of(
                "https://example.com/product?color=blue&size=m#details",
                "https://example.com/product?color=red&size=m#details",
                "https://example.com/product?color=blue&size=m#reviews",
                "https://example.com/product?size=m&color=blue#details");
        Set<String> codes = new HashSet<>();

        for (String targetUrl : targetUrls) {
            CreatedLink link = api.createLink(targetUrl);
            assertThat(codes.add(link.code())).isTrue();
            api.assertRedirect(link.code(), targetUrl);
        }

        assertThat(codes).hasSize(targetUrls.size());
        assertThat(shortLinkRepository.count()).isEqualTo(targetUrls.size());
    }

    /** Long, poorly compressible destinations can be deduplicated without truncating their redirects. */
    @Test
    void reusesLongUrlsWithoutTruncation() throws Exception {
        byte[] data = new byte[4600];
        new Random(42).nextBytes(data);
        String targetUrl = "https://example.com/product?data="
                + Base64.getUrlEncoder().withoutPadding().encodeToString(data);
        assertThat(targetUrl.length()).isGreaterThan(6000);
        CreatedLink original = api.createLink(targetUrl);

        HttpResponse<String> response = api.create(targetUrl);

        CreatedLink reused = api.assertReused(response, targetUrl);
        assertThat(reused).isEqualTo(original);
        assertThat(shortLinkRepository.count()).isEqualTo(1);
        api.assertRedirect(reused.code(), targetUrl);
    }

    /** Real HTTP verifies the header budget, exact boundary, Unicode encoding, and destination reuse. */
    @ParameterizedTest(name = "destination {index}: {1} encoded bytes")
    @MethodSource("supportedLongDestinations")
    void createsAndReusesDestinationsWithinTheEncodedLimit(String targetUrl, int encodedLength) throws Exception {
        String encodedTarget = URI.create(targetUrl).toASCIIString();
        assertThat(encodedTarget).hasSize(encodedLength);

        CreatedLink original = api.createLink(targetUrl);
        HttpResponse<String> response = api.create(targetUrl);

        CreatedLink reused = api.assertReused(response, targetUrl);
        assertThat(reused).isEqualTo(original);
        ShortLink stored = shortLinkRepository.findByCode(original.code()).orElseThrow();
        assertThat(stored.getTargetUrl()).isEqualTo(targetUrl);
        assertThat(shortLinkRepository.count()).isEqualTo(1);
        api.assertRedirect(reused.code(), encodedTarget);
    }

    /** A valid surrogate pair survives storage and reuse and becomes its UTF-8 encoded redirect. */
    @Test
    void preservesSupplementaryUnicodeInTheDestination() throws Exception {
        String targetUrl = "https://example.com/product?icon=\uD83D\uDE80#details";
        String encodedTarget = "https://example.com/product?icon=%F0%9F%9A%80#details";
        CreatedLink original = api.createLink(targetUrl);

        HttpResponse<String> response = api.create(targetUrl);

        CreatedLink reused = api.assertReused(response, targetUrl);
        assertThat(reused).isEqualTo(original);
        ShortLink stored = shortLinkRepository.findByCode(original.code()).orElseThrow();
        assertThat(stored.getTargetUrl()).isEqualTo(targetUrl);
        assertThat(shortLinkRepository.count()).isEqualTo(1);
        api.assertRedirect(reused.code(), encodedTarget);
    }

    /** An oversized ASCII or Unicode destination is rejected before any mapping is committed. */
    @ParameterizedTest(name = "oversized destination {index}")
    @MethodSource("oversizedDestinations")
    void rejectsDestinationsAboveTheEncodedLimitWithoutSaving(String targetUrl) throws Exception {
        assertThat(URI.create(targetUrl).toASCIIString()).hasSize(MAX_ENCODED_URL_LENGTH + 1);

        HttpResponse<String> response = api.create(targetUrl);

        assertBadRequest(response);
        assertThat(api.body(response).get("detail").asString())
                .isEqualTo("URL must not exceed 16384 bytes after URL encoding.");
    }

    /** Parallel requests to one server must keep every code paired with its own destination. */
    @Test
    void createsLinksConcurrentlyWithoutMixingDestinations() throws Exception {
        int requestCount = 24;
        List<String> targetUrls = new ArrayList<>();
        List<Callable<CreatedLink>> requests = new ArrayList<>();
        for (int index = 0; index < requestCount; index++) {
            String targetUrl = "https://example.com/product/" + index + "?source=rebuy#details";
            targetUrls.add(targetUrl);
            requests.add(() -> api.createLink(targetUrl));
        }

        List<CreatedLink> links = ConcurrentRequests.run(requests);

        Set<String> codes = new HashSet<>();
        for (int index = 0; index < links.size(); index++) {
            CreatedLink link = links.get(index);
            String targetUrl = targetUrls.get(index);
            assertThat(codes.add(link.code())).isTrue();
            assertThat(shortLinkRepository.findByCode(link.code()).orElseThrow().getTargetUrl())
                    .isEqualTo(targetUrl);
            api.assertRedirect(link.code(), targetUrl);
        }
        assertThat(codes).hasSize(requestCount);
        assertThat(shortLinkRepository.count()).isEqualTo(requestCount);
    }

    /** Plain HTTP destinations use the same API contract as the HTTPS success scenario. */
    @Test
    void acceptsHttpUrl() throws Exception {
        String targetUrl = "http://example.com/product/42";

        HttpResponse<String> response = api.create(targetUrl);

        api.assertCreated(response, targetUrl);
        assertThat(shortLinkRepository.count()).isEqualTo(1);
    }

    /** Bean validation and service validation both become HTTP 400 without creating a row. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ftp://example.com/product", "https://example.com/a b"})
    void rejectsInvalidUrls(String targetUrl) throws Exception {
        HttpResponse<String> response = api.create(targetUrl);

        assertBadRequest(response);
    }

    /** Missing fields and malformed JSON must use the same public error format. */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"url\":null}", "{\"url\":123}", "null", "[]", "{\"url\":"})
    void rejectsInvalidRequestBodies(String requestBody) throws Exception {
        HttpResponse<String> response = api.postJson(requestBody);

        assertBadRequest(response);
    }

    /** Raw JSON escapes reach server-side Unicode validation without client-side replacement. */
    @ParameterizedTest(name = "malformed Unicode request {index}")
    @ValueSource(strings = {
            "{\"url\":\"https://example.com/?q=\\uD800\"}",
            "{\"url\":\"https://example.com/?q=\\uDC00\"}",
            "{\"url\":\"https://example.com/?q=\\uD800x\"}"
    })
    void rejectsMalformedUnicodeWithoutSaving(String requestBody) throws Exception {
        HttpResponse<String> response = api.postJson(requestBody);

        assertBadRequest(response);
    }

    /** Checks the validation response and confirms that rejected input has no persistence effects. */
    private void assertBadRequest(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        JsonNode body = api.body(response);
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("detail").asString()).isNotBlank();
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(shortLinkRepository.count()).isZero();
    }

    /** Covers the exact ASCII/Unicode boundary and the Unicode redirect that exceeded the old 8 KiB buffer. */
    private static Stream<Arguments> supportedLongDestinations() {
        String prefix = "https://example.com/?q=";
        return Stream.of(
                Arguments.of(padToEncodedLength(prefix, MAX_ENCODED_URL_LENGTH), MAX_ENCODED_URL_LENGTH),
                Arguments.of(prefix + "я".repeat(1500), prefix.length() + 1500 * 6),
                Arguments.of(padToEncodedLength(prefix + "я".repeat(2700), MAX_ENCODED_URL_LENGTH),
                        MAX_ENCODED_URL_LENGTH));
    }

    /** Builds addresses that exceed the encoded limit even when the Unicode input has fewer characters. */
    private static Stream<String> oversizedDestinations() {
        String prefix = "https://example.com/?q=";
        return Stream.of(
                padToEncodedLength(prefix, MAX_ENCODED_URL_LENGTH + 1),
                padToEncodedLength(prefix + "я".repeat(2700), MAX_ENCODED_URL_LENGTH + 1));
    }

    /** Adds ASCII query data to produce a deterministic size after URL encoding. */
    private static String padToEncodedLength(String targetUrl, int encodedLength) {
        int paddingLength = encodedLength - URI.create(targetUrl).toASCIIString().length();
        return targetUrl + "a".repeat(paddingLength);
    }
}
