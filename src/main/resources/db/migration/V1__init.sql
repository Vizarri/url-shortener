CREATE TABLE IF NOT EXISTS short_links (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(4) NOT NULL,
    target_url TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT uk_short_links_code UNIQUE (code),
    CONSTRAINT ck_short_links_code_format CHECK (code ~ '^[a-zA-Z0-9]{4}$')
);

-- A hash index supports exact TEXT lookup without the B-tree indexed-value size limit.
-- Creators acquire a transaction advisory lock before lookup/insert on every instance.
CREATE INDEX IF NOT EXISTS ix_short_links_target_url
    ON short_links USING hash (target_url);

COMMENT ON INDEX ix_short_links_target_url IS
    'Supports exact destination lookup; application creators serialize using a URL transaction advisory lock.';

COMMENT ON TABLE short_links IS
    'Maps public four-character short codes to their original destination URLs.';

COMMENT ON COLUMN short_links.id IS
    'Internal database-generated identifier, independent of the public short code.';

COMMENT ON COLUMN short_links.code IS
    'Unique, case-sensitive four-character Base62 code used in the public short URL.';

COMMENT ON COLUMN short_links.target_url IS
    'Original destination URL, including query parameters and fragment.';

COMMENT ON COLUMN short_links.created_at IS
    'Timestamp of link creation.';
