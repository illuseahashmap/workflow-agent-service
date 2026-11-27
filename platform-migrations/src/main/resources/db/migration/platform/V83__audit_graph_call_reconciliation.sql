-- Manual external terminal evidence requires two distinct platform operators.
-- This records operator attestation, not an automated proof of a remote outcome.
CREATE TABLE agent_graph_call_reconciliation (
 id UUID PRIMARY KEY,
 token UUID NOT NULL REFERENCES agent_graph_call_permit(token),
 tenant_code VARCHAR(64) NOT NULL,
 run_id BIGINT NOT NULL,
 operation_id VARCHAR(256) NOT NULL,
 request_digest VARCHAR(64) NOT NULL,
 generation BIGINT NOT NULL,
 external_request_id VARCHAR(256) NOT NULL,
 outcome VARCHAR(32) NOT NULL CHECK(outcome IN ('COMPLETED','CANCELLED','NOT_ACCEPTED')),
 evidence_uri VARCHAR(2048) NOT NULL,
 evidence_sha256 VARCHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[a-f0-9]{64}$'),
 proposed_by VARCHAR(128) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE agent_graph_call_reconciliation_approval (
 reconciliation_id UUID PRIMARY KEY REFERENCES agent_graph_call_reconciliation(id),
 approved_by VARCHAR(128) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['agent_graph_call_reconciliation','agent_graph_call_reconciliation_approval'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY worker_access ON %I USING (current_setting(''app.system_worker'',true)=''true'') WITH CHECK (current_setting(''app.system_worker'',true)=''true'')',t);
  EXECUTE format('CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_agent_tool_snapshot_rewrite()',t);
 END LOOP;
END $$;
