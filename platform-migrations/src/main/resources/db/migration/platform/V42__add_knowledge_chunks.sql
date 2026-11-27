CREATE TABLE knowledge_chunk (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    source_code VARCHAR(128) NOT NULL,
    document_version_id BIGINT NOT NULL REFERENCES knowledge_document_version(id),
    index_version_id BIGINT NOT NULL REFERENCES knowledge_index_version(id),
    chunk_id VARCHAR(255) NOT NULL,
    ordinal INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_fingerprint VARCHAR(128) NOT NULL,
    search_vector TSVECTOR GENERATED ALWAYS AS
        (to_tsvector('simple', coalesce(content, ''))) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_knowledge_chunk_index_chunk UNIQUE (tenant_code, index_version_id, chunk_id),
    CONSTRAINT ck_knowledge_chunk_ordinal CHECK (ordinal >= 0),
    CONSTRAINT ck_knowledge_chunk_content CHECK (length(trim(content)) > 0)
);

CREATE INDEX ix_knowledge_chunk_search_vector
    ON knowledge_chunk USING GIN (search_vector);
CREATE INDEX ix_knowledge_chunk_scope
    ON knowledge_chunk (tenant_code, source_code, index_version_id, ordinal);

ALTER TABLE knowledge_chunk ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_chunk FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_knowledge_chunk ON knowledge_chunk
    USING (
        current_setting('app.platform_admin', true) = 'true'
        OR current_setting('app.system_worker', true) = 'true'
        OR tenant_code = current_setting('app.tenant_code', true)
    )
    WITH CHECK (
        current_setting('app.platform_admin', true) = 'true'
        OR current_setting('app.system_worker', true) = 'true'
        OR tenant_code = current_setting('app.tenant_code', true)
    );
