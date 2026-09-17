-- V37 databases may contain RUNNING records created before lease columns existed.
-- Their outcome cannot be proven safe to replay, so quarantine them for manual review.
UPDATE agent_tool_execution_audit
SET status = 'UNKNOWN',
    error_code = 'AGENT_TOOL_LEGACY_CLAIM_UNKNOWN'
WHERE status = 'RUNNING'
  AND lease_expires_at IS NULL;
