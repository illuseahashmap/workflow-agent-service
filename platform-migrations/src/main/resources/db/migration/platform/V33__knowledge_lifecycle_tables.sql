CREATE TABLE knowledge_source (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    source_code VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_knowledge_source_tenant_code UNIQUE (tenant_code, source_code)
);

CREATE TABLE knowledge_document_version (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    source_code VARCHAR(128) NOT NULL,
    external_document_id VARCHAR(512) NOT NULL,
    version INTEGER NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_knowledge_document_version UNIQUE
        (tenant_code, source_code, external_document_id, version)
);

CREATE TABLE knowledge_index_version (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    source_code VARCHAR(128) NOT NULL,
    version INTEGER NOT NULL,
    embedding_model VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_knowledge_index_version UNIQUE (tenant_code, source_code, version)
);

CREATE TABLE knowledge_ingestion_job (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    source_code VARCHAR(128) NOT NULL,
    document_hash VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_expires_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_knowledge_ingestion_job UNIQUE (tenant_code, source_code, document_hash)
);

CREATE TABLE knowledge_retrieval_trace (
    trace_id VARCHAR(128) PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    query_fingerprint VARCHAR(128) NOT NULL,
    authorized_scopes JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL,
    strategy VARCHAR(32) NOT NULL,
    evidence_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'knowledge_source', 'knowledge_document_version', 'knowledge_index_version',
        'knowledge_ingestion_job', 'knowledge_retrieval_trace']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING ('
            || 'current_setting(''app.platform_admin'', true) = ''true'' '
            || 'OR current_setting(''app.system_worker'', true) = ''true'' '
            || 'OR tenant_code = current_setting(''app.tenant_code'', true)) '
            || 'WITH CHECK (current_setting(''app.platform_admin'', true) = ''true'' '
            || 'OR current_setting(''app.system_worker'', true) = ''true'' '
            || 'OR tenant_code = current_setting(''app.tenant_code'', true))', table_name, table_name);
    END LOOP;
END $$;
