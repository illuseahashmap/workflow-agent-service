CREATE TABLE agent_graph_plan_policy (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, policy_json TEXT NOT NULL,
 PRIMARY KEY(tenant_code,run_id), FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_task_contract(tenant_code,run_id)
);
CREATE TABLE agent_graph_plan_verdict (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, operation_id VARCHAR(256) NOT NULL,
 candidate_ref VARCHAR(256) NOT NULL, accepted BOOLEAN NOT NULL, plan_json TEXT,
 PRIMARY KEY(tenant_code,run_id), UNIQUE(tenant_code,run_id,operation_id),
 CHECK(accepted = (plan_json IS NOT NULL)), CHECK(octet_length(plan_json)<=65536),
 FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_plan_policy(tenant_code,run_id),
 FOREIGN KEY(tenant_code,run_id,operation_id) REFERENCES agent_graph_v2_validation(tenant_code,run_id,operation_id)
);
CREATE TABLE agent_graph_plan_intent (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, intent_ref VARCHAR(256) NOT NULL,
 plan_operation_id VARCHAR(256) NOT NULL, step_id VARCHAR(128) NOT NULL,
 PRIMARY KEY(tenant_code,run_id,intent_ref),
 FOREIGN KEY(tenant_code,run_id,plan_operation_id) REFERENCES agent_graph_plan_verdict(tenant_code,run_id,operation_id)
);
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['agent_graph_plan_policy','agent_graph_plan_verdict','agent_graph_plan_intent'] LOOP
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_graph_reference_rewrite()',t||'_immutable',t);
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY %I ON %I USING (tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'') WITH CHECK (tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'')','isolate_'||t,t);
 END LOOP;
END $$;
