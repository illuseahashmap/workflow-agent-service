-- Old permits have no send fence: absence of a new fact must NEVER release them.
ALTER TABLE agent_graph_call_permit ADD COLUMN tracking_version INTEGER NOT NULL DEFAULT 0 CHECK(tracking_version IN (0,1));
ALTER TABLE agent_graph_call_permit ADD CONSTRAINT graph_permit_fact_identity UNIQUE(token,tenant_code,run_id);
CREATE TABLE agent_graph_call_dispatch (
 token UUID PRIMARY KEY, tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL,
 budget_call_attempt UUID, dispatched_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(token,tenant_code,run_id) REFERENCES agent_graph_call_permit(token,tenant_code,run_id),
 FOREIGN KEY(tenant_code,run_id,budget_call_attempt) REFERENCES agent_graph_budget_call(tenant_code,run_id,call_attempt_id)
);
CREATE TABLE agent_graph_call_outcome (
 token UUID PRIMARY KEY, tenant_code VARCHAR(64) NOT NULL, run_id BIGINT NOT NULL,
 outcome VARCHAR(32) NOT NULL CHECK(outcome IN ('NOT_DISPATCHED','RETURNED')),
 observed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(token,tenant_code,run_id) REFERENCES agent_graph_call_permit(token,tenant_code,run_id)
);
DO $$ DECLARE t TEXT; BEGIN
 FOREACH t IN ARRAY ARRAY['agent_graph_call_dispatch','agent_graph_call_outcome'] LOOP
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_graph_reference_rewrite()',t||'_immutable',t);
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY %I ON %I USING (current_setting(''app.system_worker'',true)=''true'') WITH CHECK (current_setting(''app.system_worker'',true)=''true'')','worker_'||t,t);
 END LOOP;
END $$;

-- Protocol identity is immutable; a released permit is also a permanent send fence.
CREATE FUNCTION guard_graph_call_permit_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Graph call permits are retained'; END IF;
 IF ROW(NEW.token,NEW.tenant_code,NEW.run_id,NEW.operation_id,NEW.request_digest,NEW.generation,NEW.claimed_at,NEW.tracking_version)
    IS DISTINCT FROM ROW(OLD.token,OLD.tenant_code,OLD.run_id,OLD.operation_id,OLD.request_digest,OLD.generation,OLD.claimed_at,OLD.tracking_version)
    OR (OLD.state='RELEASED' AND NEW.state<>'RELEASED') THEN
  RAISE EXCEPTION 'Graph call identity and released fence are immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER graph_call_permit_identity BEFORE UPDATE OR DELETE ON agent_graph_call_permit
 FOR EACH ROW EXECUTE FUNCTION guard_graph_call_permit_identity();

CREATE FUNCTION guard_graph_call_fact() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE p RECORD; sent BOOLEAN; finished BOOLEAN;
BEGIN
 EXECUTE format('SELECT state,tracking_version FROM %I.agent_graph_call_permit WHERE token=$1 FOR UPDATE',TG_TABLE_SCHEMA) INTO p USING NEW.token;
 IF p.tracking_version IS DISTINCT FROM 1 THEN RAISE EXCEPTION 'Physical facts require tracked permit'; END IF;
 EXECUTE format('SELECT EXISTS(SELECT 1 FROM %I.agent_graph_call_dispatch WHERE token=$1)',TG_TABLE_SCHEMA) INTO sent USING NEW.token;
 EXECUTE format('SELECT EXISTS(SELECT 1 FROM %I.agent_graph_call_outcome WHERE token=$1)',TG_TABLE_SCHEMA) INTO finished USING NEW.token;
 IF TG_TABLE_NAME='agent_graph_call_dispatch' THEN
  IF p.state<>'ACTIVE' OR sent OR finished THEN RAISE EXCEPTION 'Physical dispatch is fenced'; END IF;
 ELSE
  IF (NEW.outcome='RETURNED' AND NOT sent) OR (NEW.outcome='NOT_DISPATCHED' AND sent) THEN
   RAISE EXCEPTION 'Physical outcome conflicts with dispatch evidence';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER graph_dispatch_fence BEFORE INSERT ON agent_graph_call_dispatch FOR EACH ROW EXECUTE FUNCTION guard_graph_call_fact();
CREATE TRIGGER graph_outcome_evidence BEFORE INSERT ON agent_graph_call_outcome FOR EACH ROW EXECUTE FUNCTION guard_graph_call_fact();
