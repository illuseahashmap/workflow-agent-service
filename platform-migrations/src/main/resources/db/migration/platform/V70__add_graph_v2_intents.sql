-- Separate v2 receipts: v1 identities and serialized digests remain untouched.
CREATE TABLE agent_graph_v2_model_call (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL,
 ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 100),
 operation_id VARCHAR(256) NOT NULL, request_json TEXT NOT NULL CHECK(octet_length(request_json)<=262144),
 request_digest VARCHAR(64) NOT NULL, configuration_digest VARCHAR(64) NOT NULL,
 generation BIGINT NOT NULL, ticket UUID NOT NULL,
 physical_calls INTEGER NOT NULL DEFAULT 1 CHECK(physical_calls>0),
 response_json TEXT CHECK(octet_length(response_json)<=262144),
 receipt_json TEXT CHECK(octet_length(receipt_json)<=262144),
 candidate_ref VARCHAR(256) NOT NULL,
 PRIMARY KEY(tenant_code,run_id,ordinal), UNIQUE(tenant_code,run_id,operation_id),
 FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_execution(tenant_code,run_id)
);
CREATE TABLE agent_graph_v2_validation (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, operation_id VARCHAR(256) NOT NULL,
 request_json TEXT NOT NULL CHECK(octet_length(request_json)<=262144), request_digest VARCHAR(64) NOT NULL,
 receipt_json TEXT NOT NULL CHECK(octet_length(receipt_json)<=262144), reason_code VARCHAR(64) NOT NULL,
 PRIMARY KEY(tenant_code,run_id,operation_id),
 FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_execution(tenant_code,run_id)
);
CREATE TRIGGER graph_v2_model_response_immutable BEFORE UPDATE ON agent_graph_v2_model_call
 FOR EACH ROW EXECUTE FUNCTION guard_graph_model_response();
CREATE FUNCTION guard_graph_v2_request() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.operation_id IS DISTINCT FROM OLD.operation_id OR NEW.request_json IS DISTINCT FROM OLD.request_json THEN
  RAISE EXCEPTION 'Graph v2 request identity is immutable' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER graph_v2_request_immutable BEFORE UPDATE ON agent_graph_v2_model_call
 FOR EACH ROW EXECUTE FUNCTION guard_graph_v2_request();
CREATE TRIGGER graph_v2_model_undeletable BEFORE DELETE ON agent_graph_v2_model_call
 FOR EACH ROW EXECUTE FUNCTION reject_graph_reference_rewrite();
CREATE TRIGGER graph_v2_validation_immutable BEFORE UPDATE OR DELETE ON agent_graph_v2_validation
 FOR EACH ROW EXECUTE FUNCTION reject_graph_reference_rewrite();
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['agent_graph_v2_model_call','agent_graph_v2_validation'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY %I ON %I USING (tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'') WITH CHECK (tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'')','isolate_'||t,t);
 END LOOP;
END $$;
