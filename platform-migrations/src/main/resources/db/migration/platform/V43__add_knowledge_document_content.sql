CREATE TABLE knowledge_document_content (
    document_version_id BIGINT PRIMARY KEY REFERENCES knowledge_document_version(id),
    tenant_code VARCHAR(64) NOT NULL,
    source_code VARCHAR(128) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_knowledge_document_content_not_blank CHECK (length(trim(content)) > 0)
);

CREATE INDEX ix_knowledge_document_content_tenant_source
    ON knowledge_document_content (tenant_code, source_code, document_version_id);

ALTER TABLE knowledge_document_content ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_document_content FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_knowledge_document_content ON knowledge_document_content
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
