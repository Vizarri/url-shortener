package com.rebuy.urlshortener.integration.service;

import com.rebuy.urlshortener.db.model.ShortLink;
import com.rebuy.urlshortener.db.repository.ShortLinkRepository;
import com.rebuy.urlshortener.integration.BaseIntegrationTest;
import com.rebuy.urlshortener.service.ShortCodeGenerator;
import com.rebuy.urlshortener.service.ShortLinkService;
import com.rebuy.urlshortener.support.ConcurrentRequests;
import com.rebuy.urlshortener.support.LinkApiClient.CreatedLink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Verifies collision recovery and transaction boundaries using deterministic candidates and real PostgreSQL. */
class ShortLinkCollisionIntegrationTest extends BaseIntegrationTest {

    private static final String ORIGINAL_CODE = "aB7x";
    private static final String ORIGINAL_URL = "https://example.com/original";
    private static final String NEW_URL = "https://example.com/new";

    @MockitoBean
    private ShortCodeGenerator shortCodeGenerator;

    @Autowired
    private ShortLinkRepository shortLinkRepository;

    @Autowired
    private ShortLinkService shortLinkService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** An enclosing transaction is rejected before candidate generation or persistence starts. */
    @Test
    void rejectsCreationInsideAnExistingTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> shortLinkService.create(NEW_URL)))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(shortCodeGenerator);
        assertThat(shortLinkRepository.count()).isZero();
    }

    /** A retry must commit after the failed attempt has rolled back, without replacing the original link. */
    @Test
    void retriesDuplicateCodeInANewTransactionAndPreservesOriginalLink() throws Exception {
        Long originalId = fixtures.insert(ORIGINAL_CODE, ORIGINAL_URL);
        when(shortCodeGenerator.generate()).thenReturn(ORIGINAL_CODE, "C9dQ");

        CreatedLink link = api.createLink(NEW_URL);

        assertThat(link.code()).isEqualTo("C9dQ");
        assertOriginalLinkUnchanged(originalId);
        ShortLink created = shortLinkRepository.findByCode(link.code()).orElseThrow();
        assertThat(created.getId()).isNotEqualTo(originalId);
        assertThat(created.getTargetUrl()).isEqualTo(NEW_URL);
        assertThat(shortLinkRepository.count()).isEqualTo(2);
        verify(shortCodeGenerator, times(2)).generate();
    }

    /** Two in-flight creators using one candidate must both succeed and retain their own destinations. */
    @Test
    void retriesWhenConcurrentRequestsGenerateTheSameCode() throws Exception {
        CyclicBarrier firstCandidates = new CyclicBarrier(2);
        AtomicInteger generatedCandidates = new AtomicInteger();
        when(shortCodeGenerator.generate()).thenAnswer(invocation -> {
            if (generatedCandidates.getAndIncrement() < 2) {
                firstCandidates.await(5, TimeUnit.SECONDS);
                return ORIGINAL_CODE;
            }
            return "C9dQ";
        });
        List<String> targetUrls = List.of("https://example.com/first", "https://example.com/second");
        List<Callable<CreatedLink>> requests = new ArrayList<>();
        for (String targetUrl : targetUrls) {
            requests.add(() -> api.createLink(targetUrl));
        }

        List<CreatedLink> links = ConcurrentRequests.run(requests);

        assertThat(links).extracting(CreatedLink::code).containsExactlyInAnyOrder(ORIGINAL_CODE, "C9dQ");
        for (int index = 0; index < links.size(); index++) {
            CreatedLink link = links.get(index);
            String targetUrl = targetUrls.get(index);
            assertThat(shortLinkRepository.findByCode(link.code()).orElseThrow().getTargetUrl())
                    .isEqualTo(targetUrl);
            api.assertRedirect(link.code(), targetUrl);
        }
        assertThat(shortLinkRepository.count()).isEqualTo(2);
        verify(shortCodeGenerator, times(3)).generate();
    }

    /** The attempt limit must produce a service error while preserving the existing link. */
    @Test
    void returnsServiceUnavailableAfterTenCollisions() throws Exception {
        Long originalId = fixtures.insert(ORIGINAL_CODE, ORIGINAL_URL);
        when(shortCodeGenerator.generate()).thenReturn(ORIGINAL_CODE);

        HttpResponse<String> response = api.create(NEW_URL);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        JsonNode body = api.body(response);
        assertThat(body.get("status").asInt()).isEqualTo(503);
        assertThat(body.get("detail").asString()).isNotBlank();
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertOriginalLinkUnchanged(originalId);
        assertThat(shortLinkRepository.count()).isEqualTo(1);
        verify(shortCodeGenerator, times(10)).generate();
    }

    /** A format constraint failure must propagate without being retried as a code collision. */
    @Test
    void doesNotRetryUnrelatedConstraintViolation() throws Exception {
        when(shortCodeGenerator.generate()).thenReturn("bad!", "C9dQ");

        HttpResponse<String> response = api.create(NEW_URL);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(shortLinkRepository.count()).isZero();
        verify(shortCodeGenerator, times(1)).generate();
    }

    /** Confirms that a rejected insert cannot modify the previously assigned destination or row identity. */
    private void assertOriginalLinkUnchanged(Long originalId) {
        ShortLink original = shortLinkRepository.findByCode(ORIGINAL_CODE).orElseThrow();
        assertThat(original.getId()).isEqualTo(originalId);
        assertThat(original.getTargetUrl()).isEqualTo(ORIGINAL_URL);
    }
}
