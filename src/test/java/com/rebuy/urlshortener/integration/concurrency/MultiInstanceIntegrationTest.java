package com.rebuy.urlshortener.integration.concurrency;

import com.rebuy.urlshortener.service.ShortCodeGenerator;
import com.rebuy.urlshortener.support.ConcurrentRequests;
import com.rebuy.urlshortener.support.LinkApiClient;
import com.rebuy.urlshortener.support.LinkApiClient.CreatedLink;
import com.rebuy.urlshortener.support.TestApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * Verifies two independent application contexts in one JVM, each with its own HTTP
 * server and persistence context, against one isolated PostgreSQL database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiInstanceIntegrationTest {

    private final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private TestApplication first;
    private TestApplication second;

    /** Starts independent applications without using the local Compose database. */
    @BeforeAll
    void startInstances() {
        postgres.start();
        first = TestApplication.start(postgres);
        second = TestApplication.start(postgres);

        assertThat(first.api().baseUrl()).isNotEqualTo(second.api().baseUrl());
        assertThat(first.generator()).isNotSameAs(second.generator());
    }

    /** Closes every started resource in order, collecting failures without skipping later resources. */
    @AfterAll
    void stopInstances() {
        assertAll("Close multi-instance test resources",
                () -> {
                    if (second != null) {
                        second.close();
                    }
                },
                () -> {
                    if (first != null) {
                        first.close();
                    }
                },
                postgres::stop);
    }

    /** Restores real generation and removes data left by the previous scenario. */
    @BeforeEach
    void clearLinks() {
        reset(first.generator(), second.generator());
        first.repository().deleteAll();
    }

    /** Committed links must be immediately readable through the other instance. */
    @Test
    void resolvesLinksCreatedByEitherInstance() throws Exception {
        String firstTarget = "https://example.com/first?source=rebuy#details";
        String secondTarget = "https://example.com/second?source=rebuy#details";

        CreatedLink firstLink = first.api().createLink(firstTarget);
        CreatedLink secondLink = second.api().createLink(secondTarget);

        second.api().assertRedirect(firstLink.code(), firstTarget);
        first.api().assertRedirect(secondLink.code(), secondTarget);
        assertThat(second.repository().count()).isEqualTo(2);
    }

    /** Parallel writes through both instances must preserve every destination. */
    @Test
    void createsLinksConcurrentlyAcrossInstances() throws Exception {
        int requestCount = 24;
        List<String> targetUrls = new ArrayList<>();
        List<Callable<CreatedLink>> requests = new ArrayList<>();
        for (int index = 0; index < requestCount; index++) {
            LinkApiClient api = index % 2 == 0 ? first.api() : second.api();
            String targetUrl = "https://example.com/product/" + index + "?source=rebuy#details";
            targetUrls.add(targetUrl);
            requests.add(() -> api.createLink(targetUrl));
        }

        List<CreatedLink> links = ConcurrentRequests.run(requests);

        assertThat(links).hasSize(requestCount)
                .extracting(CreatedLink::code).doesNotHaveDuplicates();
        for (int index = 0; index < requestCount; index++) {
            assertReadableThroughBothInstances(links.get(index), targetUrls.get(index));
        }
        assertThat(first.repository().count()).isEqualTo(requestCount);
    }

    /** Concurrent requests for one destination must allocate exactly one shared mapping. */
    @Test
    void reusesOneLinkForConcurrentRequestsAcrossInstances() throws Exception {
        int requestCount = 24;
        String targetUrl = "https://example.com/shared-product?source=rebuy#details";
        List<Callable<HttpResponse<String>>> requests = new ArrayList<>();
        for (int index = 0; index < requestCount; index++) {
            LinkApiClient api = index % 2 == 0 ? first.api() : second.api();
            requests.add(() -> api.create(targetUrl));
        }

        List<HttpResponse<String>> responses = ConcurrentRequests.run(requests);

        assertThat(responses).filteredOn(response -> response.statusCode() == 201).hasSize(1);
        assertThat(responses).filteredOn(response -> response.statusCode() == 200).hasSize(requestCount - 1);
        List<CreatedLink> links = new ArrayList<>();
        for (int index = 0; index < requestCount; index++) {
            LinkApiClient api = index % 2 == 0 ? first.api() : second.api();
            links.add(assertCreatedOrReused(api, responses.get(index), targetUrl));
        }
        assertThat(links).extracting(CreatedLink::code).containsOnly(links.getFirst().code());
        assertReadableThroughBothInstances(links.getFirst(), targetUrl);
        assertThat(first.repository().count()).isEqualTo(1);
    }

    /** Concurrent first requests for one destination must generate a code only on the winning instance. */
    @Test
    void resolvesConcurrentFirstCreationsToTheSameCode() throws Exception {
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        useCandidate(first.generator(), firstCalls, "A1b2");
        useCandidate(second.generator(), secondCalls, "C3d4");
        String targetUrl = "https://example.com/shared-product?source=rebuy#details";
        List<Callable<HttpResponse<String>>> requests = List.of(
                () -> first.api().create(targetUrl),
                () -> second.api().create(targetUrl));

        List<HttpResponse<String>> responses = ConcurrentRequests.run(requests);

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 200);
        CreatedLink firstLink = assertCreatedOrReused(first.api(), responses.get(0), targetUrl);
        CreatedLink secondLink = assertCreatedOrReused(second.api(), responses.get(1), targetUrl);
        assertThat(firstLink.code()).isEqualTo(secondLink.code()).isIn("A1b2", "C3d4");
        assertReadableThroughBothInstances(firstLink, targetUrl);
        assertThat(firstCalls.get() + secondCalls.get()).isEqualTo(1);
        assertThat(first.repository().count()).isEqualTo(1);
    }

    /** A shared candidate must cause exactly one retry without overwriting either URL. */
    @Test
    void retriesWhenBothInstancesGenerateTheSameCandidate() throws Exception {
        CyclicBarrier candidatesReady = new CyclicBarrier(2);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        collideOnFirstCall(first.generator(), firstCalls, candidatesReady, "A1b2");
        collideOnFirstCall(second.generator(), secondCalls, candidatesReady, "C3d4");
        String firstTarget = "https://example.com/first";
        String secondTarget = "https://example.com/second";
        List<Callable<CreatedLink>> requests = List.of(
                () -> first.api().createLink(firstTarget),
                () -> second.api().createLink(secondTarget));

        List<CreatedLink> links = ConcurrentRequests.run(requests);

        assertThat(links).extracting(CreatedLink::code)
                .doesNotHaveDuplicates().contains("aB7x");
        assertReadableThroughBothInstances(links.get(0), firstTarget);
        assertReadableThroughBothInstances(links.get(1), secondTarget);
        assertThat(firstCalls.get() + secondCalls.get()).isEqualTo(3);
        assertThat(first.repository().count()).isEqualTo(2);
    }

    /** Checks that both HTTP servers preserve the code's original destination. */
    private void assertReadableThroughBothInstances(CreatedLink link, String targetUrl) throws Exception {
        first.api().assertRedirect(link.code(), targetUrl);
        second.api().assertRedirect(link.code(), targetUrl);
    }

    /** Checks either success outcome against the origin of the instance that handled the request. */
    private CreatedLink assertCreatedOrReused(LinkApiClient api, HttpResponse<String> response, String targetUrl) {
        return response.statusCode() == 201
                ? api.assertCreated(response, targetUrl)
                : api.assertReused(response, targetUrl);
    }

    /** Supplies an instance-specific candidate and counts actual allocations. */
    private void useCandidate(ShortCodeGenerator generator, AtomicInteger calls, String candidate) {
        doAnswer(invocation -> {
            calls.incrementAndGet();
            return candidate;
        }).when(generator).generate();
    }

    /** Synchronizes the first candidates before allowing a distinct retry on either instance. */
    private void collideOnFirstCall(ShortCodeGenerator generator, AtomicInteger calls,
                                    CyclicBarrier candidatesReady, String retryCode) {
        doAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                candidatesReady.await(10, TimeUnit.SECONDS);
                return "aB7x";
            }
            return retryCode;
        }).when(generator).generate();
    }
}
