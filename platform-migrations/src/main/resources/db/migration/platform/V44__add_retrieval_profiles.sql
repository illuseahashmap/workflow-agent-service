CREATE TABLE knowledge_retrieval_profile_version (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    profile_code VARCHAR(128) NOT NULL,
    version INTEGER NOT NULL,
    strategy VARCHAR(32) NOT NULL,
    max_results INTEGER NOT NULL,
    minimum_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    index_version_id BIGINT NOT NULL REFERENCES knowledge_index_version(id),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_knowledge_retrieval_profile_version
        UNIQUE (tenant_code, profile_code, version),
    CONSTRAINT uk_knowledge_retrieval_profile_version_tenant_id
        UNIQUE (id, tenant_code),
    CONSTRAINT ck_knowledge_retrieval_profile_max_results
        CHECK (max_results BETWEEN 1 AND 50),
    CONSTRAINT ck_knowledge_retrieval_profile_minimum_score
        CHECK (minimum_score >= 0)
);

CREATE TABLE knowledge_retrieval_profile_scope (
    profile_version_id BIGINT NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    scope_code VARCHAR(128) NOT NULL,
    PRIMARY KEY (profile_version_id, scope_code),
    CONSTRAINT fk_knowledge_retrieval_profile_scope_tenant
        FOREIGN KEY (profile_version_id, tenant_code)
        REFERENCES knowledge_retrieval_profile_version(id, tenant_code)
);

ALTER TABLE knowledge_retrieval_profile_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_retrieval_profile_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_knowledge_retrieval_profile_version
    ON knowledge_retrieval_profile_version
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

ALTER TABLE knowledge_retrieval_profile_scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_retrieval_profile_scope FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_knowledge_retrieval_profile_scope
    ON knowledge_retrieval_profile_scope
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
