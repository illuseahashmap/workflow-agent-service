CREATE TABLE agent_graph_task_contract (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL,
 contract_json TEXT NOT NULL CHECK(octet_length(contract_json)<=65536), contract_digest VARCHAR(64) NOT NULL,
 PRIMARY KEY(tenant_code,run_id), FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_run_budget(tenant_code,run_id)
);
-- No backfill: existing v2 Runs did not opt in to this frozen contract/correction policy.
CREATE TABLE agent_graph_correction (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL,
 operation_id VARCHAR(256) NOT NULL, trigger_operation_id VARCHAR(256) NOT NULL, trigger_digest VARCHAR(64) NOT NULL,
 source_ordinal INTEGER NOT NULL, reason VARCHAR(64) NOT NULL CHECK(reason='LOOP_DETECTED'),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_code,run_id), UNIQUE(tenant_code,run_id,operation_id),
 FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_task_contract(tenant_code,run_id),
 FOREIGN KEY(tenant_code,run_id,source_ordinal) REFERENCES agent_graph_v2_model_call(tenant_code,run_id,ordinal)
);
CREATE TABLE agent_graph_correction_result (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, operation_id VARCHAR(256) NOT NULL,
 receipt_ref VARCHAR(256) NOT NULL,
 PRIMARY KEY(tenant_code,run_id),
 FOREIGN KEY(tenant_code,run_id,operation_id) REFERENCES agent_graph_correction(tenant_code,run_id,operation_id)
);
CREATE TABLE agent_graph_fact_validation (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, operation_id VARCHAR(256) NOT NULL,
 candidate_ref VARCHAR(256) NOT NULL, report_json TEXT NOT NULL CHECK(octet_length(report_json)<=262144),
 PRIMARY KEY(tenant_code,run_id,operation_id),
 FOREIGN KEY(tenant_code,run_id,operation_id) REFERENCES agent_graph_v2_validation(tenant_code,run_id,operation_id)
);
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['agent_graph_task_contract','agent_graph_correction','agent_graph_correction_result','agent_graph_fact_validation'] LOOP
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_graph_reference_rewrite()',t||'_immutable',t);
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY %I ON %I USING (tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'') WITH CHECK (tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'')','isolate_'||t,t);
 END LOOP;
END $$;
