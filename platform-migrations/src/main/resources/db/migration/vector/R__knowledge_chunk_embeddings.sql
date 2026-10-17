-- Applied only with the vector Spring profile, after pgvector is installed on PostgreSQL.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS knowledge_chunk_embedding (
    tenant_code VARCHAR(64) NOT NULL,
    source_code VARCHAR(128) NOT NULL,
    index_version_id BIGINT NOT NULL REFERENCES knowledge_index_version(id),
    chunk_id VARCHAR(255) NOT NULL,
    model_version VARCHAR(255) NOT NULL,
    dimension INTEGER NOT NULL CHECK (dimension BETWEEN 1 AND 4096),
    embedding vector NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_code, index_version_id, chunk_id),
    FOREIGN KEY (tenant_code, index_version_id, chunk_id)
        REFERENCES knowledge_chunk(tenant_code, index_version_id, chunk_id),
    CHECK (vector_dims(embedding) = dimension)
);

CREATE INDEX IF NOT EXISTS ix_knowledge_embedding_scope
    ON knowledge_chunk_embedding (tenant_code, source_code, index_version_id, model_version);

ALTER TABLE knowledge_chunk_embedding ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_chunk_embedding FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation_knowledge_chunk_embedding ON knowledge_chunk_embedding;
CREATE POLICY tenant_isolation_knowledge_chunk_embedding ON knowledge_chunk_embedding
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
