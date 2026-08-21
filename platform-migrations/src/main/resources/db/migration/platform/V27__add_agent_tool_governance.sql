CREATE TABLE agent_tool_definition (
    tool_code VARCHAR(128) PRIMARY KEY,
    tool_name VARCHAR(255) NOT NULL,
    input_schema TEXT NOT NULL,
    read_only BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_tool_tenant_grant (
    tenant_code VARCHAR(64) NOT NULL,
    tool_code VARCHAR(128) NOT NULL REFERENCES agent_tool_definition (tool_code),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_code, tool_code)
);

CREATE TABLE agent_tool_execution_audit (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    tool_code VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    arguments_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    output_snapshot TEXT,
    error_code VARCHAR(64),
    trace_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_tool_audit_key UNIQUE (tenant_code, tool_code, idempotency_key)
);

CREATE INDEX idx_agent_tool_audit_tenant_time
    ON agent_tool_execution_audit (tenant_code, created_at DESC);

INSERT INTO agent_tool_definition (tool_code, tool_name, input_schema, read_only)
VALUES (
    'agent_run_status',
    '查询 Agent 运行状态',
    '{"type":"object","required":["runId"],"properties":{"runId":{"type":"integer"}}}',
    TRUE
)
ON CONFLICT (tool_code) DO NOTHING;

INSERT INTO agent_tool_tenant_grant (tenant_code, tool_code)
SELECT tenant_code, 'agent_run_status' FROM workflow_tenant
ON CONFLICT (tenant_code, tool_code) DO NOTHING;

CREATE OR REPLACE FUNCTION grant_builtin_agent_tools()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    INSERT INTO agent_tool_tenant_grant (tenant_code, tool_code)
    VALUES (NEW.tenant_code, 'agent_run_status')
    ON CONFLICT (tenant_code, tool_code) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_workflow_tenant_agent_tools
    AFTER INSERT ON workflow_tenant
    FOR EACH ROW EXECUTE FUNCTION grant_builtin_agent_tools();

ALTER TABLE agent_tool_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_tool_definition FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_tool_tenant_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_tool_tenant_grant FORCE ROW LEVEL SECURITY;
ALTER TABLE agent_tool_execution_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_tool_execution_audit FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_agent_tool_definition ON agent_tool_definition
    USING (current_setting('app.platform_admin', true) = 'true'
        OR current_setting('app.system_worker', true) = 'true');
CREATE POLICY tenant_isolation_agent_tool_tenant_grant ON agent_tool_tenant_grant
    USING (current_setting('app.platform_admin', true) = 'true'
        OR current_setting('app.system_worker', true) = 'true'
        OR tenant_code = current_setting('app.tenant_code', true));
CREATE POLICY tenant_isolation_agent_tool_execution_audit ON agent_tool_execution_audit
    USING (current_setting('app.platform_admin', true) = 'true'
        OR current_setting('app.system_worker', true) = 'true'
        OR tenant_code = current_setting('app.tenant_code', true))
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR current_setting('app.system_worker', true) = 'true'
        OR tenant_code = current_setting('app.tenant_code', true));
