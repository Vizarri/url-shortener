package com.rebuy.urlshortener.integration;

import com.rebuy.urlshortener.UrlShortenerApplication;
import com.rebuy.urlshortener.config.PostgresTestConfiguration;
import com.rebuy.urlshortener.support.LinkApiClient;
import com.rebuy.urlshortener.support.ShortLinkFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shares application startup and test-data lifecycle while preserving real transaction boundaries. */
@SpringBootTest(classes = UrlShortenerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
public abstract class BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${local.server.port}")
    private int port;

    protected LinkApiClient api;
    protected ShortLinkFixtures fixtures;

    /** Starts each scenario with an empty table and a client bound to this server. */
    @BeforeEach
    protected void prepareScenario() {
        api = new LinkApiClient(port);
        fixtures = new ShortLinkFixtures(jdbcTemplate);
        fixtures.clear();
    }

    /** Closes the client even if scenario setup or assertions failed. */
    @AfterEach
    protected void closeClient() {
        if (api != null) {
            api.close();
        }
    }
}
