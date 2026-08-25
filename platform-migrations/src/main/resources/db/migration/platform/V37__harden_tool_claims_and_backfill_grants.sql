-- Backfill tenants created after V35 but before the V36 trigger correction.
INSERT INTO agent_tool_tenant_grant (tenant_code, tool_code)
SELECT tenant_code, 'workflow_process_context'
FROM workflow_tenant
ON CONFLICT (tenant_code, tool_code) DO NOTHING;

ALTER TABLE agent_tool_execution_audit
    ADD COLUMN claim_owner VARCHAR(128),
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN fencing_token BIGINT NOT NULL DEFAULT 0;

-- These records predate claim leases. Their external outcome cannot be proven
-- safe to replay, so keep them for manual verification instead of retrying.
UPDATE agent_tool_execution_audit
SET status = 'UNKNOWN',
    error_code = 'AGENT_TOOL_LEGACY_CLAIM_UNKNOWN'
WHERE status = 'RUNNING'
  AND lease_expires_at IS NULL;

CREATE INDEX idx_agent_tool_audit_claim_recovery
    ON agent_tool_execution_audit (status, lease_expires_at)
    WHERE status = 'RUNNING';
