package com.rebuy.urlshortener.integration.db;

import com.rebuy.urlshortener.db.model.ShortLink;
import com.rebuy.urlshortener.db.repository.ShortLinkRepository;
import com.rebuy.urlshortener.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks repeatable initialization DDL, preservation of existing data, and schema comments. */
class InitialMigrationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShortLinkRepository repository;

    /** Re-executes the SQL itself, rather than a Flyway call that would skip an applied migration. */
    @Test
    void canRepeatInitializationWithoutLosingData() {
        String targetUrl = "https://example.com/product/42";
        Long savedId = fixtures.insert("aB7x", targetUrl);
        ResourceDatabasePopulator migration = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V1__init.sql"));

        migration.execute(dataSource);

        ShortLink found = repository.findByCode("aB7x").orElseThrow();
        assertThat(found.getId()).isEqualTo(savedId);
        assertThat(found.getTargetUrl()).isEqualTo(targetUrl);
        assertThat(repository.count()).isEqualTo(1);
        assertSchemaIsDocumented();
    }

    /** Checks the table comment and every non-system column comment in PostgreSQL metadata. */
    private void assertSchemaIsDocumented() {
        String tableComment = jdbcTemplate.queryForObject(
                "SELECT obj_description('short_links'::regclass, 'pg_class')", String.class);
        List<String> columnComments = jdbcTemplate.queryForList("""
                SELECT col_description(attrelid, attnum)
                FROM pg_attribute
                WHERE attrelid = 'short_links'::regclass
                  AND attnum > 0
                  AND NOT attisdropped
                ORDER BY attnum
                """, String.class);

        assertThat(tableComment).isNotBlank();
        assertThat(columnComments).isNotEmpty()
                .allSatisfy(comment -> assertThat(comment).isNotBlank());
    }
}
