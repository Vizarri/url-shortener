package com.rebuy.urlshortener.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Supplies an isolated PostgreSQL database for a Spring integration-test context. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    /**
     * Creates a container whose lifecycle and connection details are managed by Spring.
     *
     * @return PostgreSQL container with a dynamically assigned host port
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:16-alpine");
    }
}
