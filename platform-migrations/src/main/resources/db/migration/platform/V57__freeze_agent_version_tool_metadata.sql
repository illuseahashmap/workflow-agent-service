CREATE TABLE agent_version_tool_snapshot (
    tenant_code VARCHAR(64) NOT NULL,
    agent_version_id BIGINT NOT NULL,
    tool_code VARCHAR(255) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    input_schema JSONB NOT NULL,
    read_only BOOLEAN NOT NULL,
    capability_type VARCHAR(32) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    side_effect BOOLEAN NOT NULL,
    provenance VARCHAR(32) NOT NULL CHECK (provenance IN ('PUBLICATION','UPGRADE_BASELINE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(tenant_code,agent_version_id,tool_code),
    FOREIGN KEY(agent_version_id,tenant_code) REFERENCES agent_definition_version(id,tenant_code)
);
-- Capture the deployment's current behavior explicitly. This cannot reconstruct historical metadata.
INSERT INTO agent_version_tool_snapshot
    (tenant_code,agent_version_id,tool_code,tool_name,input_schema,read_only,capability_type,risk_level,side_effect,provenance)
SELECT DISTINCT v.tenant_code,v.id,d.tool_code,d.tool_name,CAST(d.input_schema AS jsonb),d.read_only,d.capability_type,d.risk_level,d.side_effect,
       'UPGRADE_BASELINE'
FROM agent_definition_version v
CROSS JOIN LATERAL jsonb_array_elements_text(v.tool_set_json) code
JOIN agent_tool_definition d ON d.tool_code=code.value
WHERE v.status='PUBLISHED';

ALTER TABLE agent_version_tool_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_version_tool_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_tool_snapshot ON agent_version_tool_snapshot
USING (tenant_code=current_setting('app.tenant_code',true)
    OR current_setting('app.system_worker',true)='true' OR current_setting('app.platform_admin',true)='true')
WITH CHECK (tenant_code=current_setting('app.tenant_code',true)
    OR current_setting('app.system_worker',true)='true' OR current_setting('app.platform_admin',true)='true');
CREATE FUNCTION reject_agent_tool_snapshot_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Published tool metadata is immutable' USING ERRCODE='23514';
END $$;
CREATE TRIGGER agent_tool_snapshot_immutable BEFORE UPDATE OR DELETE ON agent_version_tool_snapshot
FOR EACH ROW EXECUTE FUNCTION reject_agent_tool_snapshot_rewrite();
