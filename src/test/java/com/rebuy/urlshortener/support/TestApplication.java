package com.rebuy.urlshortener.support;

import com.rebuy.urlshortener.UrlShortenerApplication;
import com.rebuy.urlshortener.db.repository.ShortLinkRepository;
import com.rebuy.urlshortener.service.ShortCodeGenerator;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.mockito.Mockito.spy;

/** Owns one independently started HTTP application and its test client. */
public final class TestApplication implements AutoCloseable {

    private final ConfigurableApplicationContext context;
    private final LinkApiClient api;

    private TestApplication(ConfigurableApplicationContext context, LinkApiClient api) {
        this.context = context;
        this.api = api;
    }

    /** Starts an application with its own random port, pool, and generator against the supplied database. */
    public static TestApplication start(PostgreSQLContainer postgres) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                UrlShortenerApplication.class, GeneratorConfiguration.class)
                .run("--server.port=0",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword());
        try {
            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            return new TestApplication(context, new LinkApiClient(port));
        } catch (RuntimeException | Error exception) {
            context.close();
            throw exception;
        }
    }

    /** Returns this instance's real HTTP client. */
    public LinkApiClient api() {
        return api;
    }

    /** Returns this instance's repository for explicit persistence assertions. */
    public ShortLinkRepository repository() {
        return context.getBean(ShortLinkRepository.class);
    }

    /** Returns this instance's independently created generator spy. */
    public ShortCodeGenerator generator() {
        return context.getBean(ShortCodeGenerator.class);
    }

    /** Closes the client before its server, even if closing the client fails. */
    @Override
    public void close() {
        try {
            api.close();
        } finally {
            context.close();
        }
    }

    /** Supplies one controllable generator per explicitly started application context. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class GeneratorConfiguration {

        /** Creates a real generator spy without adding production test hooks. */
        @Bean
        @Primary
        ShortCodeGenerator testCodeGenerator() {
            return spy(new ShortCodeGenerator());
        }
    }
}
