CREATE TABLE knowledge_principal_scope_grant (
    tenant_code VARCHAR(64) NOT NULL,
    principal_id VARCHAR(128) NOT NULL,
    scope_code VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_code, principal_id, scope_code)
);

ALTER TABLE knowledge_principal_scope_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_principal_scope_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_knowledge_principal_scope_grant
    ON knowledge_principal_scope_grant
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
