CREATE TABLE mcp_connector (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    connector_code VARCHAR(128) NOT NULL,
    connector_name VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mcp_connector_tenant_code UNIQUE (tenant_code, connector_code),
    CONSTRAINT uk_mcp_connector_id_tenant UNIQUE (id, tenant_code),
    CONSTRAINT ck_mcp_connector_status CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED'))
);

CREATE TABLE mcp_connector_version (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    connector_id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    endpoint_url VARCHAR(2048) NOT NULL,
    protocol_version VARCHAR(64) NOT NULL DEFAULT '2025-03-26',
    credential_ref VARCHAR(255),
    timeout_seconds INTEGER NOT NULL DEFAULT 30,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mcp_connector_version UNIQUE (tenant_code, connector_id, version),
    CONSTRAINT uk_mcp_connector_version_id_tenant UNIQUE (id, tenant_code),
    CONSTRAINT fk_mcp_connector_version_connector FOREIGN KEY (connector_id, tenant_code)
        REFERENCES mcp_connector (id, tenant_code) ON DELETE CASCADE,
    CONSTRAINT ck_mcp_connector_version_endpoint CHECK (endpoint_url LIKE 'https://%'),
    CONSTRAINT ck_mcp_connector_version_timeout CHECK (timeout_seconds BETWEEN 1 AND 300),
    CONSTRAINT ck_mcp_connector_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED'))
);

CREATE TABLE mcp_tool_catalog_version (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    connector_version_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    content_fingerprint VARCHAR(128) NOT NULL,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    reviewed_by VARCHAR(128),
    CONSTRAINT uk_mcp_catalog_version_id_tenant UNIQUE (id, tenant_code),
    CONSTRAINT fk_mcp_catalog_connector_version FOREIGN KEY (connector_version_id, tenant_code)
        REFERENCES mcp_connector_version (id, tenant_code) ON DELETE CASCADE,
    CONSTRAINT ck_mcp_catalog_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED'))
);

CREATE TABLE mcp_tool_snapshot (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    catalog_version_id BIGINT NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    input_schema TEXT NOT NULL,
    schema_fingerprint VARCHAR(128) NOT NULL,
    risk_level VARCHAR(32) NOT NULL DEFAULT 'READ_ONLY',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_mcp_tool_snapshot_name UNIQUE (tenant_code, catalog_version_id, tool_name),
    CONSTRAINT uk_mcp_tool_snapshot_id_tenant UNIQUE (id, tenant_code),
    CONSTRAINT fk_mcp_tool_snapshot_catalog FOREIGN KEY (catalog_version_id, tenant_code)
        REFERENCES mcp_tool_catalog_version (id, tenant_code) ON DELETE CASCADE,
    CONSTRAINT ck_mcp_tool_snapshot_risk CHECK (risk_level = 'READ_ONLY')
);

CREATE TABLE agent_version_mcp_tool_binding (
    tenant_code VARCHAR(64) NOT NULL,
    agent_version_id BIGINT NOT NULL,
    tool_snapshot_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_code, agent_version_id, tool_snapshot_id),
    CONSTRAINT fk_agent_mcp_binding_version FOREIGN KEY (agent_version_id, tenant_code)
        REFERENCES agent_definition_version (id, tenant_code) ON DELETE CASCADE,
    CONSTRAINT fk_agent_mcp_binding_snapshot FOREIGN KEY (tool_snapshot_id, tenant_code)
        REFERENCES mcp_tool_snapshot (id, tenant_code) ON DELETE RESTRICT
);

CREATE INDEX idx_mcp_connector_version_tenant ON mcp_connector_version (tenant_code, connector_id, version DESC);
CREATE INDEX idx_mcp_catalog_tenant ON mcp_tool_catalog_version (tenant_code, connector_version_id, discovered_at DESC);
CREATE INDEX idx_mcp_snapshot_tenant ON mcp_tool_snapshot (tenant_code, catalog_version_id, tool_name);

DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'mcp_connector', 'mcp_connector_version', 'mcp_tool_catalog_version',
        'mcp_tool_snapshot', 'agent_version_mcp_tool_binding'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('CREATE POLICY tenant_isolation_%I ON %I USING ('
            || '(current_setting(''app.platform_admin'', true) = ''true'' '
            || 'OR current_setting(''app.system_worker'', true) = ''true'' '
            || 'OR tenant_code = current_setting(''app.tenant_code'', true)) '
            || 'WITH CHECK ('
            || '(current_setting(''app.platform_admin'', true) = ''true'' '
            || 'OR current_setting(''app.system_worker'', true) = ''true'' '
            || 'OR tenant_code = current_setting(''app.tenant_code'', true))', table_name, table_name);
    END LOOP;
END $$;
