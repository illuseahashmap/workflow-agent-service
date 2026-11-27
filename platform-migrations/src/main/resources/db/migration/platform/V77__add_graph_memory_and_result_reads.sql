ALTER TABLE agent_graph_budget_operation DROP CONSTRAINT agent_graph_budget_operation_kind_check;
ALTER TABLE agent_graph_budget_operation ADD CHECK(kind IN ('MODEL','TOOL','VALIDATE','READ_RESULT'));
CREATE TABLE agent_graph_memory_policy (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, policy_json TEXT NOT NULL,
 PRIMARY KEY(tenant_code,run_id), FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_run_budget(tenant_code,run_id)
);
CREATE TABLE agent_graph_memory (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, reference VARCHAR(256) NOT NULL,
 operation_id VARCHAR(256) NOT NULL, parent_ref VARCHAR(256), sources_json TEXT NOT NULL,
 notes TEXT NOT NULL CHECK(octet_length(notes)<=32768), receipt_ref VARCHAR(256) NOT NULL,
 PRIMARY KEY(tenant_code,run_id,reference), UNIQUE(tenant_code,run_id,operation_id),
 FOREIGN KEY(tenant_code,run_id,operation_id) REFERENCES agent_graph_v2_model_call(tenant_code,run_id,operation_id),
 FOREIGN KEY(tenant_code,run_id,parent_ref) REFERENCES agent_graph_memory(tenant_code,run_id,reference)
);
CREATE TABLE agent_graph_read_intent (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, intent_ref VARCHAR(256) NOT NULL,
 model_operation VARCHAR(256) NOT NULL, source_ref VARCHAR(256) NOT NULL, pointer TEXT NOT NULL,
 PRIMARY KEY(tenant_code,run_id,intent_ref), UNIQUE(tenant_code,run_id,model_operation),
 FOREIGN KEY(tenant_code,run_id,model_operation) REFERENCES agent_graph_v2_model_call(tenant_code,run_id,operation_id)
);
CREATE TABLE agent_graph_read_result (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, operation_id VARCHAR(256) NOT NULL,
 intent_ref VARCHAR(256) NOT NULL, reference VARCHAR(256) NOT NULL, status VARCHAR(32) NOT NULL,
 content TEXT NOT NULL CHECK(octet_length(content)<=32768), receipt_json TEXT NOT NULL,
 PRIMARY KEY(tenant_code,run_id,operation_id), UNIQUE(tenant_code,run_id,intent_ref), UNIQUE(tenant_code,run_id,reference),
 FOREIGN KEY(tenant_code,run_id,intent_ref) REFERENCES agent_graph_read_intent(tenant_code,run_id,intent_ref),
 FOREIGN KEY(tenant_code,run_id,operation_id) REFERENCES agent_graph_budget_operation(tenant_code,run_id,operation_id),
 CHECK(status IN ('OK','TOO_LARGE','MISSING_FIELD','NOT_JSON'))
);
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['agent_graph_memory_policy','agent_graph_memory','agent_graph_read_intent','agent_graph_read_result'] LOOP
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_graph_reference_rewrite()',t||'_immutable',t);
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY %I ON %I USING (tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'') WITH CHECK (tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'')','isolate_'||t,t);
 END LOOP;
END $$;
