package com.rebuy.urlshortener.unit.service;

import com.rebuy.urlshortener.db.model.ShortLink;
import com.rebuy.urlshortener.db.repository.ShortLinkRepository;
import com.rebuy.urlshortener.dto.ShortLinkCreationResult;
import com.rebuy.urlshortener.exception.ShortCodeCollisionException;
import com.rebuy.urlshortener.service.ShortCodeGenerator;
import com.rebuy.urlshortener.service.ShortLinkDbService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Tests entity isolation and database-error classification without starting JPA or Spring. */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class ShortLinkDbServiceTest {

    private static final String TARGET_URL = "https://example.com/product";

    @Mock
    private ShortLinkRepository repository;

    @Mock
    private ShortCodeGenerator generator;

    @InjectMocks
    private ShortLinkDbService service;

    @Captor
    private ArgumentCaptor<ShortLink> insertedLinks;

    /** Only the named code constraint with PostgreSQL's unique-violation state is a retryable collision. */
    @Test
    void translatesCodeCollisionAndRetainsOriginalFailure() {
        DataIntegrityViolationException failure = integrityFailure("23505", "uk_short_links_code");
        when(repository.findByTargetUrl(TARGET_URL)).thenReturn(Optional.empty());
        when(generator.generate()).thenReturn("aB7x");
        when(repository.saveAndFlush(any(ShortLink.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.findOrCreate(TARGET_URL))
                .isInstanceOf(ShortCodeCollisionException.class)
                .hasCause(failure);

        verify(repository, times(1)).saveAndFlush(any(ShortLink.class));
    }

    /** Neither a different constraint, a different SQL state, nor a bare JDBC error is a code collision. */
    @ParameterizedTest
    @MethodSource("unrelatedIntegrityFailures")
    void preservesUnrelatedIntegrityFailure(DataIntegrityViolationException failure) {
        when(repository.findByTargetUrl(TARGET_URL)).thenReturn(Optional.empty());
        when(generator.generate()).thenReturn("aB7x");
        when(repository.saveAndFlush(any(ShortLink.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.findOrCreate(TARGET_URL))
                .isSameAs(failure);

        verify(repository, times(1)).saveAndFlush(any(ShortLink.class));
    }

    /** An existing URL is found under the lock without generating a code or inserting a row. */
    @Test
    void reusesExistingLinkAfterAcquiringTheUrlLock() {
        ShortLink existing = new ShortLink();
        existing.setCode("aB7x");
        existing.setTargetUrl(TARGET_URL);
        when(repository.findByTargetUrl(TARGET_URL)).thenReturn(Optional.of(existing));

        ShortLinkCreationResult result = service.findOrCreate(TARGET_URL);

        assertThat(result.link()).isSameAs(existing);
        assertThat(result.created()).isFalse();
        InOrder persistence = inOrder(repository);
        persistence.verify(repository).lockTargetUrl(TARGET_URL);
        persistence.verify(repository).findByTargetUrl(TARGET_URL);
        persistence.verifyNoMoreInteractions();
        verifyNoInteractions(generator);
    }

    /** Every insert gets its own entity, so a later attempt cannot mutate an earlier candidate. */
    @Test
    void createsFreshEntityForEachInsert() {
        String firstUrl = "https://example.com/first";
        String secondUrl = "https://example.com/second";
        when(repository.findByTargetUrl(firstUrl)).thenReturn(Optional.empty());
        when(repository.findByTargetUrl(secondUrl)).thenReturn(Optional.empty());
        when(generator.generate()).thenReturn("aB7x", "C9dQ");
        when(repository.saveAndFlush(any(ShortLink.class))).thenAnswer(returnsFirstArg());

        ShortLinkCreationResult firstResult = service.findOrCreate(firstUrl);
        ShortLinkCreationResult secondResult = service.findOrCreate(secondUrl);

        InOrder persistence = inOrder(repository, generator);
        persistence.verify(repository).lockTargetUrl(firstUrl);
        persistence.verify(repository).findByTargetUrl(firstUrl);
        persistence.verify(generator).generate();
        persistence.verify(repository).saveAndFlush(insertedLinks.capture());
        persistence.verify(repository).lockTargetUrl(secondUrl);
        persistence.verify(repository).findByTargetUrl(secondUrl);
        persistence.verify(generator).generate();
        persistence.verify(repository).saveAndFlush(insertedLinks.capture());
        persistence.verifyNoMoreInteractions();
        List<ShortLink> attempts = insertedLinks.getAllValues();
        ShortLink first = firstResult.link();
        ShortLink second = secondResult.link();
        assertThat(firstResult.created()).isTrue();
        assertThat(secondResult.created()).isTrue();
        assertThat(first).isSameAs(attempts.get(0));
        assertThat(second).isSameAs(attempts.get(1)).isNotSameAs(first);
        assertThat(first.getCode()).isEqualTo("aB7x");
        assertThat(first.getTargetUrl()).isEqualTo(firstUrl);
        assertThat(second.getCode()).isEqualTo("C9dQ");
        assertThat(second.getTargetUrl()).isEqualTo(secondUrl);
    }

    /** Supplies failures for which the two-part collision classification must not match. */
    private static Stream<DataIntegrityViolationException> unrelatedIntegrityFailures() {
        return Stream.of(
                integrityFailure("23505", "uk_another_constraint"),
                integrityFailure("23514", "uk_short_links_code"),
                new DataIntegrityViolationException("JDBC failure without a named Hibernate constraint",
                        new SQLException("Insert rejected", "23505")));
    }

    /** Builds a realistic translated failure with an extra wrapper in the Hibernate cause chain. */
    private static DataIntegrityViolationException integrityFailure(String sqlState, String constraint) {
        SQLException sqlFailure = new SQLException("Insert rejected", sqlState);
        ConstraintViolationException violation = new ConstraintViolationException(
                "Constraint rejected the insert", sqlFailure, constraint);
        return new DataIntegrityViolationException("Insert failed: " + sqlState + ", " + constraint,
                new IllegalStateException("Persistence failure", violation));
    }
}
