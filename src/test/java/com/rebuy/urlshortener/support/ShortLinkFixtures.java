package com.rebuy.urlshortener.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** Seeds test data through SQL independently of the link creation API. */
public final class ShortLinkFixtures {

    private final JdbcTemplate jdbcTemplate;

    /** Uses the test database supplied by the owning application context. */
    public ShortLinkFixtures(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Removes links without relying on generated ID values or resetting sequences. */
    public void clear() {
        jdbcTemplate.update("DELETE FROM short_links");
    }

    /**
     * Inserts a fixture, allowing the database to enforce every production constraint.
     *
     * @param code public code to reserve
     * @param targetUrl destination assigned to that code
     * @return database-generated internal identifier
     */
    public Long insert(String code, String targetUrl) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO short_links (code, target_url, created_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, code, targetUrl);
    }
}
