package com.rebuy.urlshortener.unit.service;

import com.rebuy.urlshortener.db.model.ShortLink;
import com.rebuy.urlshortener.dto.ShortLinkCreationResult;
import com.rebuy.urlshortener.exception.InvalidUrlException;
import com.rebuy.urlshortener.exception.ShortCodeAllocationException;
import com.rebuy.urlshortener.exception.ShortCodeCollisionException;
import com.rebuy.urlshortener.exception.ShortLinkNotFoundException;
import com.rebuy.urlshortener.service.ShortLinkDbService;
import com.rebuy.urlshortener.service.ShortLinkService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.net.URI;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Checks destination validation and allocation decisions without Spring, HTTP, or a database. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ShortLinkServiceTest {

    private static final String TARGET_URL = "https://example.com/product/42?query=phone%20case&path=%2F#details";
    private static final String FIRST_CODE = "aB7x";
    private static final int MAX_ENCODED_URL_LENGTH = 16_384;

    @Mock
    private ShortLinkDbService dbService;

    @InjectMocks
    private ShortLinkService service;

    /** Supported schemes, the valid port boundary, and supplementary Unicode reach persistence unchanged. */
    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com/product/42", "https://example.com/product/42",
            "HTTPS://example.com/product/42", "https://example.com:65535/product/42",
            "https://example.com/product?icon=\uD83D\uDE80#details"
    })
    void createsLinkForSupportedDestination(String targetUrl) {
        ShortLink saved = new ShortLink();
        saved.setCode(FIRST_CODE);
        saved.setTargetUrl(targetUrl);
        ShortLinkCreationResult expected = new ShortLinkCreationResult(saved, true);
        when(dbService.findOrCreate(targetUrl)).thenReturn(expected);

        ShortLinkCreationResult result = service.create(targetUrl);

        assertThat(result).isSameAs(expected);
        assertThat(result.created()).isTrue();
        verify(dbService).findOrCreate(targetUrl);
        verifyNoMoreInteractions(dbService);
    }

    /** Outer whitespace is removed while encoded components, query parameters, and fragments survive. */
    @Test
    void trimsWhitespaceWithoutRewritingTheDestination() {
        ShortLink saved = new ShortLink();
        saved.setCode(FIRST_CODE);
        saved.setTargetUrl(TARGET_URL);
        ShortLinkCreationResult expected = new ShortLinkCreationResult(saved, true);
        when(dbService.findOrCreate(TARGET_URL)).thenReturn(expected);

        ShortLinkCreationResult result = service.create(" \t" + TARGET_URL + "\n ");

        assertThat(result).isSameAs(expected);
        verify(dbService).findOrCreate(TARGET_URL);
        verifyNoMoreInteractions(dbService);
    }

    /** ASCII and Unicode destinations at the encoded size limit reach persistence without rewriting. */
    @ParameterizedTest(name = "destination {index} at encoded size limit")
    @MethodSource("destinationsAtEncodedLimit")
    void acceptsDestinationAtEncodedLimitAfterTrimmingWhitespace(String targetUrl) {
        assertThat(URI.create(targetUrl).toASCIIString()).hasSize(MAX_ENCODED_URL_LENGTH);
        ShortLink saved = new ShortLink();
        saved.setCode(FIRST_CODE);
        saved.setTargetUrl(targetUrl);
        ShortLinkCreationResult expected = new ShortLinkCreationResult(saved, true);
        when(dbService.findOrCreate(targetUrl)).thenReturn(expected);

        ShortLinkCreationResult result = service.create(" \t" + targetUrl + "\n ");

        assertThat(result).isSameAs(expected);
        verify(dbService).findOrCreate(targetUrl);
        verifyNoMoreInteractions(dbService);
    }

    /** The encoded size, rather than the original character count, is checked before persistence. */
    @ParameterizedTest(name = "destination {index} exceeds encoded size limit")
    @MethodSource("destinationsExceedingEncodedLimit")
    void rejectsOversizedDestinationBeforeCallingTheDatabase(String targetUrl) {
        assertThat(URI.create(targetUrl).toASCIIString()).hasSize(MAX_ENCODED_URL_LENGTH + 1);

        assertThatThrownBy(() -> service.create(targetUrl))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessage("URL must not exceed 16384 bytes after URL encoding.");

        verifyNoInteractions(dbService);
    }

    /** Reusing a stored URL preserves its original code and stops after the first database result. */
    @Test
    void returnsExistingLinkWithoutAnotherAllocationAttempt() {
        ShortLink existing = new ShortLink();
        existing.setCode("C9dQ");
        existing.setTargetUrl(TARGET_URL);
        ShortLinkCreationResult expected = new ShortLinkCreationResult(existing, false);
        when(dbService.findOrCreate(TARGET_URL)).thenReturn(expected);

        ShortLinkCreationResult result = service.create(TARGET_URL);

        assertThat(result).isSameAs(expected);
        assertThat(result.created()).isFalse();
        verify(dbService).findOrCreate(TARGET_URL);
        verifyNoMoreInteractions(dbService);
    }

    /** A code collision starts another database attempt for the same validated destination. */
    @Test
    void retriesAfterCodeCollision() {
        ShortLink saved = new ShortLink();
        saved.setCode("C9dQ");
        saved.setTargetUrl(TARGET_URL);
        ShortLinkCreationResult expected = new ShortLinkCreationResult(saved, true);
        ShortCodeCollisionException collision = new ShortCodeCollisionException(
                new DataIntegrityViolationException("Duplicate code"));
        when(dbService.findOrCreate(TARGET_URL)).thenThrow(collision).thenReturn(expected);

        ShortLinkCreationResult result = service.create(TARGET_URL);

        assertThat(result).isSameAs(expected);
        verify(dbService, times(2)).findOrCreate(TARGET_URL);
        verifyNoMoreInteractions(dbService);
    }

    /** Exhaustion is bounded and retains the final collision for diagnostics. */
    @Test
    void stopsAfterTenCollisionsAndRetainsTheLastCause() {
        ShortCodeCollisionException firstCollision = new ShortCodeCollisionException(
                new DataIntegrityViolationException("First collision"));
        ShortCodeCollisionException lastCollision = new ShortCodeCollisionException(
                new DataIntegrityViolationException("Final collision"));
        when(dbService.findOrCreate(TARGET_URL)).thenThrow(firstCollision, lastCollision);

        assertThatThrownBy(() -> service.create(TARGET_URL))
                .isInstanceOfSatisfying(ShortCodeAllocationException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(lastCollision));

        verify(dbService, times(10)).findOrCreate(TARGET_URL);
        verifyNoMoreInteractions(dbService);
    }

    /** An unrelated persistence failure is propagated immediately instead of consuming retries. */
    @ParameterizedTest
    @MethodSource("nonCollisionFailures")
    void doesNotRetryOtherDatabaseFailures(RuntimeException failure) {
        when(dbService.findOrCreate(TARGET_URL)).thenThrow(failure);

        assertThatThrownBy(() -> service.create(TARGET_URL)).isSameAs(failure);

        verify(dbService).findOrCreate(TARGET_URL);
        verifyNoMoreInteractions(dbService);
    }

    /** Invalid destinations are rejected before the database service is called. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ", "not-a-url", "/relative/path", "//example.com/path",
            "ftp://example.com/product", "javascript:alert(1)", "file:///tmp/product",
            "https://", "https:///path", "https://example.com/a b", "https://example.com/%zz",
            "https://example.com:65536/path", "https://example.com:-1/path"
    })
    void rejectsInvalidDestinationBeforeAllocatingACode(String targetUrl) {
        assertThatThrownBy(() -> service.create(targetUrl)).isInstanceOf(InvalidUrlException.class);

        verifyNoInteractions(dbService);
    }

    /** Invalid surrogate sequences are rejected before persistence or URI encoding can fail. */
    @ParameterizedTest(name = "malformed UTF-16 destination {index}")
    @MethodSource("destinationsWithMalformedUnicode")
    void rejectsMalformedUnicodeBeforeCallingTheDatabase(String targetUrl) {
        assertThatThrownBy(() -> service.create(targetUrl)).isInstanceOf(InvalidUrlException.class);

        verifyNoInteractions(dbService);
    }

    /** Resolution returns the stored URL verbatim and never allocates a new code. */
    @Test
    void resolvesTheExactStoredDestination() {
        ShortLink stored = new ShortLink();
        stored.setCode(FIRST_CODE);
        stored.setTargetUrl(TARGET_URL);
        when(dbService.findByCode(FIRST_CODE)).thenReturn(Optional.of(stored));

        String targetUrl = service.getTargetUrl(FIRST_CODE);

        assertThat(targetUrl).isEqualTo(TARGET_URL);
        verify(dbService).findByCode(FIRST_CODE);
        verifyNoMoreInteractions(dbService);
    }

    /** An unknown code produces a domain lookup error without allocating or creating a link. */
    @Test
    void reportsNotFoundForUnknownCode() {
        when(dbService.findByCode("zzZZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTargetUrl("zzZZ"))
                .isInstanceOf(ShortLinkNotFoundException.class);

        verify(dbService).findByCode("zzZZ");
        verifyNoMoreInteractions(dbService);
    }

    /** Supplies integrity and availability failures that must never be mistaken for collisions. */
    private static Stream<RuntimeException> nonCollisionFailures() {
        return Stream.of(new DataIntegrityViolationException("Required column missing"),
                new DataAccessResourceFailureException("Database unavailable"));
    }

    /** Supplies missing, standalone, and incorrectly paired UTF-16 surrogates. */
    private static Stream<String> destinationsWithMalformedUnicode() {
        String prefix = "https://example.com/?q=";
        return Stream.of(
                prefix + '\uD800',
                prefix + '\uDC00',
                prefix + '\uD800' + "x",
                prefix + '\uD800' + '\uD800');
    }

    /** Supplies both a plain ASCII address and a much shorter Unicode address with the same encoded size. */
    private static Stream<String> destinationsAtEncodedLimit() {
        String prefix = "https://example.com/?q=";
        String unicodePrefix = prefix + "я".repeat(2700);
        return Stream.of(
                prefix + "a".repeat(MAX_ENCODED_URL_LENGTH - prefix.length()),
                unicodePrefix + "a".repeat(
                        MAX_ENCODED_URL_LENGTH - URI.create(unicodePrefix).toASCIIString().length()));
    }

    /** Extends each supported boundary case by one ASCII byte. */
    private static Stream<String> destinationsExceedingEncodedLimit() {
        return destinationsAtEncodedLimit().map(targetUrl -> targetUrl + "a");
    }
}
