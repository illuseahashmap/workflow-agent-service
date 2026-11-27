-- V2 Run-lifetime accounting. No generation/Attempt field is part of a budget identity.
ALTER TABLE agent_graph_execution ADD COLUMN stop_retry_at TIMESTAMPTZ;
ALTER TABLE agent_graph_execution ADD COLUMN stop_failures INTEGER NOT NULL DEFAULT 0 CHECK(stop_failures>=0);
CREATE TABLE agent_graph_run_budget (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL,
 logical_limit INTEGER NOT NULL CHECK(logical_limit BETWEEN 1 AND 300),
 physical_limit INTEGER NOT NULL CHECK(physical_limit BETWEEN 1 AND 300),
 validation_limit INTEGER NOT NULL CHECK(validation_limit BETWEEN 1 AND 100),
 PRIMARY KEY(tenant_code,run_id),
 FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_execution(tenant_code,run_id)
);
INSERT INTO agent_graph_run_budget
 SELECT tenant_code,run_id,(binding_json::jsonb->>'max_model_calls')::int+(binding_json::jsonb->>'max_tool_calls')::int,
 (binding_json::jsonb->>'max_model_calls')::int+(binding_json::jsonb->>'max_tool_calls')::int,
 (binding_json::jsonb->>'max_model_calls')::int
 FROM agent_graph_execution WHERE (binding_json::jsonb->>'state_schema_version')::int=2;
CREATE TABLE agent_graph_budget_operation (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, operation_id VARCHAR(256) NOT NULL,
 kind VARCHAR(16) NOT NULL CHECK(kind IN ('MODEL','TOOL','VALIDATE')), request_digest VARCHAR(64) NOT NULL,
 PRIMARY KEY(tenant_code,run_id,operation_id),
 FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_run_budget(tenant_code,run_id)
);
CREATE TABLE agent_graph_budget_call (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, call_attempt_id UUID NOT NULL,
 operation_id VARCHAR(256) NOT NULL, generation BIGINT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_code,run_id,call_attempt_id),
 FOREIGN KEY(tenant_code,run_id,operation_id) REFERENCES agent_graph_budget_operation(tenant_code,run_id,operation_id)
);
CREATE TABLE agent_graph_budget_usage (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, call_attempt_id UUID NOT NULL,
 input_tokens BIGINT NOT NULL CHECK(input_tokens>=0), output_tokens BIGINT NOT NULL CHECK(output_tokens>=0),
 PRIMARY KEY(tenant_code,run_id,call_attempt_id),
 FOREIGN KEY(tenant_code,run_id,call_attempt_id) REFERENCES agent_graph_budget_call(tenant_code,run_id,call_attempt_id)
);
CREATE TABLE agent_graph_run_stop (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, reason VARCHAR(64) NOT NULL,
 stopped_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_code,run_id),
 FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_execution(tenant_code,run_id)
);
CREATE TABLE agent_graph_stop_completion (
 tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL, completed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 outcome VARCHAR(16) NOT NULL CHECK(outcome IN ('COMMITTED','DISCARDED','BLOCKED')),
 PRIMARY KEY(tenant_code,run_id),
 FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_run_stop(tenant_code,run_id)
);
-- Existing v2 reservations predate accounting: never reset consumed budget by pretending they are fresh.
INSERT INTO agent_graph_run_stop(tenant_code,run_id,reason)
 SELECT tenant_code,run_id,'GRAPH_BUDGET_MIGRATION_REQUIRED' FROM agent_graph_run_budget;
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['agent_graph_run_budget','agent_graph_budget_operation','agent_graph_budget_call',
                         'agent_graph_budget_usage','agent_graph_run_stop','agent_graph_stop_completion'] LOOP
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_graph_reference_rewrite()',t||'_immutable',t);
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY %I ON %I USING (tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'') WITH CHECK (tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'')','isolate_'||t,t);
 END LOOP;
END $$;

-- Legacy operator resume/retry extends deadlines and clears attempts. It must not silently
-- mutate a frozen v2 Run; native/v1 behavior remains unchanged. Graph defer/takeover changes neither.
CREATE FUNCTION guard_graph_v2_run_lifetime() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE is_protected BOOLEAN;
BEGIN
 -- Use the trigger table's schema, never the caller's search_path (also works for non-owner roles).
 EXECUTE format('SELECT EXISTS(SELECT 1 FROM %I.agent_graph_run_budget WHERE tenant_code=$1 AND run_id=$2)', TG_TABLE_SCHEMA)
  INTO is_protected USING OLD.tenant_code, OLD.id;
 IF is_protected THEN
  IF NEW.deadline_at IS DISTINCT FROM OLD.deadline_at
     OR (OLD.status IN ('SUCCEEDED','FAILED','TIMED_OUT','CANCELLED') AND NEW.status IS DISTINCT FROM OLD.status)
     OR (OLD.current_attempt_id IS NOT NULL AND NEW.current_attempt_id IS DISTINCT FROM OLD.current_attempt_id
         AND NEW.status NOT IN ('SUCCEEDED','FAILED','TIMED_OUT','CANCELLED')) THEN
   RAISE EXCEPTION 'Graph v2 Run lifetime is immutable; use a new admitted Run' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER graph_v2_run_lifetime BEFORE UPDATE ON agent_run
 FOR EACH ROW EXECUTE FUNCTION guard_graph_v2_run_lifetime();
