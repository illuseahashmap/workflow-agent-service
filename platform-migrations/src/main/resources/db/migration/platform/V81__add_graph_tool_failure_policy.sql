-- Independent of V80 capacity administration. Existing reservations retain their physical ceiling.
-- Trusted transactional migration must see all tenants under FORCE RLS.
SELECT set_config('app.system_worker','true',true);
CREATE TABLE agent_graph_tool_retry_policy (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL,
 attempt_limit INTEGER NOT NULL CHECK(attempt_limit BETWEEN 1 AND 300),
 PRIMARY KEY(tenant_code,run_id),
 FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_run_budget(tenant_code,run_id)
);
INSERT INTO agent_graph_tool_retry_policy SELECT tenant_code,run_id,physical_limit FROM agent_graph_run_budget;
CREATE TABLE agent_graph_tool_failure (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, call_attempt_id UUID NOT NULL,
 error_code VARCHAR(64) NOT NULL, retryable BOOLEAN NOT NULL,
 failed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_code,run_id,call_attempt_id),
 FOREIGN KEY(tenant_code,run_id,call_attempt_id) REFERENCES agent_graph_budget_call(tenant_code,run_id,call_attempt_id)
);
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['agent_graph_tool_retry_policy','agent_graph_tool_failure'] LOOP
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_graph_reference_rewrite()',t||'_immutable',t);
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY %I ON %I USING (tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'') WITH CHECK (tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'')','isolate_'||t,t);
 END LOOP;
END $$;

-- A failed read-only call stays QUARANTINED and counted. A bounded retry uses a distinct permit.
-- The serialized admission guard still blocks unknown calls without a trusted transient failure.
DROP INDEX graph_call_inflight;
CREATE UNIQUE INDEX graph_call_inflight ON agent_graph_call_permit(tenant_code,run_id,operation_id) WHERE state='ACTIVE';
