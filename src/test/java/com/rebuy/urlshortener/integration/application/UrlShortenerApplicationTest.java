package com.rebuy.urlshortener.integration.application;

import com.rebuy.urlshortener.integration.BaseIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks startup and frontend HTTP availability; these checks do not execute JavaScript.
 */
class UrlShortenerApplicationTest extends BaseIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    /**
     * Checks database connectivity, completed Flyway migrations, and application health.
     *
     * @throws Exception if the database or HTTP health check cannot be completed
     */
    @Test
    void startsWithPostgresAndFlywayAndReportsHealthy() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.isValid(5)).isTrue();
        }
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().pending()).isEmpty();

        HttpResponse<String> response = api.get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(api.body(response).get("status").asString()).isEqualTo("UP");
    }

    /** The application root must serve its embedded HTML page. */
    @Test
    void servesTheEmbeddedHomepage() throws Exception {
        HttpResponse<String> response = api.get("/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/html");
        assertThat(response.body()).isNotBlank();
    }

    /** Fixed JAR entry timestamps must never cause stale JavaScript or CSS to be reused. */
    @ParameterizedTest
    @ValueSource(strings = {"/app.js", "/styles.css"})
    void servesFreshAssetsEvenWithAnOldJarTimestamp(String path) throws Exception {
        HttpResponse<String> response = api.get(path);

        assertFreshAssetResponse(response);
        String contentType = response.headers().firstValue("Content-Type").orElseThrow().split(";")[0];
        if (path.equals("/app.js")) {
            assertThat(contentType).isIn("text/javascript", "application/javascript");
        } else {
            assertThat(contentType).isEqualTo("text/css");
        }

        HttpResponse<String> conditionalResponse = api.get(path,
                Map.of("If-Modified-Since", "Fri, 01 Feb 1980 00:00:00 GMT"));

        assertFreshAssetResponse(conditionalResponse);
        assertThat(conditionalResponse.body()).isEqualTo(response.body());
    }

    /** Checks that clients receive a complete body without a reusable timestamp validator. */
    private void assertFreshAssetResponse(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().firstValue("Last-Modified")).isEmpty();
        assertThat(response.body()).isNotBlank();
    }
}
