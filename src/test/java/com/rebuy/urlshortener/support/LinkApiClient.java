package com.rebuy.urlshortener.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Sends real HTTP requests and checks the common successful-link contract. */
public final class LinkApiClient implements AutoCloseable {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Creates a client for one server; redirects stay visible to the test. */
    public LinkApiClient(int port) {
        baseUrl = "http://localhost:" + port;
    }

    /** Returns this instance's HTTP origin for assertions about absolute links. */
    public String baseUrl() {
        return baseUrl;
    }

    /** Submits a destination without assuming that it is valid or that creation succeeds. */
    public HttpResponse<String> create(String targetUrl) throws IOException, InterruptedException {
        return postJson(jsonMapper.writeValueAsString(Map.of("url", targetUrl)));
    }

    /** Submits raw JSON, allowing tests to exercise malformed and missing request fields. */
    public HttpResponse<String> postJson(String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/links"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Requests a path beginning with a slash, without following redirects. */
    public HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return get(path, Map.of());
    }

    /** Requests a path with explicit headers, including conditional cache validators. */
    public HttpResponse<String> get(String path, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        headers.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Parses a response using a test-owned JSON model rather than production DTOs. */
    public JsonNode body(HttpResponse<String> response) {
        return jsonMapper.readTree(response.body());
    }

    /**
     * Checks the shared creation contract while keeping scenario-specific assertions in tests.
     *
     * @param response response from the creation endpoint
     * @param expectedTarget destination submitted by the caller
     * @return the public fields of the committed link
     */
    public CreatedLink assertCreated(HttpResponse<String> response, String expectedTarget) {
        return assertLinkResponse(response, expectedTarget, 201);
    }

    /** Checks that a repeated destination returns its existing mapping instead of creating a row. */
    public CreatedLink assertReused(HttpResponse<String> response, String expectedTarget) {
        return assertLinkResponse(response, expectedTarget, 200);
    }

    /** Checks the public fields and Location header for either successful creation outcome. */
    private CreatedLink assertLinkResponse(HttpResponse<String> response, String expectedTarget, int status) {
        assertThat(response.statusCode()).as("link response status: %s", response.body()).isEqualTo(status);
        JsonNode json = body(response);
        CreatedLink link = new CreatedLink(json.get("code").asString(),
                json.get("shortUrl").asString(), json.get("targetUrl").asString());
        assertThat(link.code()).matches("[a-zA-Z0-9]{4}");
        assertThat(link.shortUrl()).isEqualTo(baseUrl + "/" + link.code());
        assertThat(link.targetUrl()).isEqualTo(expectedTarget);
        assertThat(response.headers().firstValue("Location")).contains(link.shortUrl());
        return link;
    }

    /** Creates a link and checks the common success contract before returning its fields. */
    public CreatedLink createLink(String targetUrl) throws IOException, InterruptedException {
        return assertCreated(create(targetUrl), targetUrl);
    }

    /** Checks that this instance redirects a code to its exact destination. */
    public void assertRedirect(String code, String targetUrl) throws IOException, InterruptedException {
        HttpResponse<String> response = get("/" + code);
        assertThat(response.statusCode()).as("redirect status for %s", code).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).contains(targetUrl);
    }

    /** Releases HTTP connections and client worker threads after the owning test finishes. */
    @Override
    public void close() {
        client.close();
    }

    /**
     * Public API fields used in success scenarios.
     *
     * @param code allocated four-character code
     * @param shortUrl absolute short URL returned by the application
     * @param targetUrl original destination returned by the application
     */
    public record CreatedLink(String code, String shortUrl, String targetUrl) {
    }
}
