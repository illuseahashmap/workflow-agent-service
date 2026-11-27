CREATE TABLE agent_graph_loop_policy (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, policy_version INTEGER NOT NULL CHECK(policy_version=1),
 policy_json TEXT NOT NULL CHECK(octet_length(policy_json)<=65536),
 PRIMARY KEY(tenant_code,run_id), FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_run_budget(tenant_code,run_id)
);
-- Historical observations are reconstructed from immutable receipts, never initialized as an empty history.
INSERT INTO agent_graph_loop_policy
 SELECT tenant_code,run_id,1,'{"ignored_argument_fields":{},"ignored_result_fields":{}}' FROM agent_graph_run_budget;
CREATE TABLE agent_graph_loop_verdict (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, operation_id VARCHAR(256) NOT NULL,
 request_digest VARCHAR(64) NOT NULL, policy_version INTEGER NOT NULL CHECK(policy_version=1),
 action_digest VARCHAR(64) NOT NULL, evidence_json TEXT NOT NULL CHECK(octet_length(evidence_json)<=262144),
 pattern VARCHAR(64) NOT NULL CHECK(pattern IN ('ALLOW','REPEATED_ACTION_RESULT','SHORT_CYCLE_2','SHORT_CYCLE_3')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_code,run_id,operation_id), FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_loop_policy(tenant_code,run_id)
);
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['agent_graph_loop_policy','agent_graph_loop_verdict'] LOOP
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_graph_reference_rewrite()',t||'_immutable',t);
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY %I ON %I USING (tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'') WITH CHECK (tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'')','isolate_'||t,t);
 END LOOP;
END $$;
