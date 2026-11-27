-- No model credentials, graph cursor, or business completion state are stored here.
CREATE TABLE agent_graph_model_call (
    tenant_code VARCHAR(64) NOT NULL,
    run_id BIGINT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 1 AND 100),
    request_digest VARCHAR(64) NOT NULL,
    configuration_digest VARCHAR(64) NOT NULL,
    generation BIGINT NOT NULL,
    ticket UUID NOT NULL,
    physical_calls INTEGER NOT NULL DEFAULT 1 CHECK (physical_calls>0),
    response_json TEXT CHECK (octet_length(response_json)<=262144),
    receipt_json TEXT CHECK (octet_length(receipt_json)<=262144),
    candidate_ref VARCHAR(256) NOT NULL,
    PRIMARY KEY(tenant_code,run_id,ordinal),
    FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_execution(tenant_code,run_id)
);
CREATE TABLE agent_graph_validation (
    tenant_code VARCHAR(64) NOT NULL,
    run_id BIGINT NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    receipt_json TEXT NOT NULL CHECK (octet_length(receipt_json)<=262144),
    reason_code VARCHAR(64) NOT NULL,
    PRIMARY KEY(tenant_code,run_id),
    FOREIGN KEY(tenant_code,run_id) REFERENCES agent_graph_execution(tenant_code,run_id)
);
CREATE FUNCTION guard_graph_model_response() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.tenant_code,NEW.run_id,NEW.ordinal,NEW.request_digest,NEW.configuration_digest,NEW.candidate_ref)
       IS DISTINCT FROM ROW(OLD.tenant_code,OLD.run_id,OLD.ordinal,OLD.request_digest,OLD.configuration_digest,OLD.candidate_ref)
       OR (OLD.response_json IS NOT NULL AND NEW.response_json IS DISTINCT FROM OLD.response_json)
       OR (OLD.receipt_json IS NOT NULL AND NEW.receipt_json IS DISTINCT FROM OLD.receipt_json) THEN
        RAISE EXCEPTION 'Committed model identity/response/receipt is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER graph_model_response_immutable BEFORE UPDATE ON agent_graph_model_call
    FOR EACH ROW EXECUTE FUNCTION guard_graph_model_response();
CREATE TRIGGER graph_model_response_undeletable BEFORE DELETE ON agent_graph_model_call
    FOR EACH ROW EXECUTE FUNCTION reject_graph_reference_rewrite();
CREATE TRIGGER graph_validation_immutable BEFORE UPDATE OR DELETE ON agent_graph_validation
    FOR EACH ROW EXECUTE FUNCTION reject_graph_reference_rewrite();
DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['agent_graph_model_call','agent_graph_validation'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
        EXECUTE format('CREATE POLICY %I ON %I USING ('
            || 'tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'') WITH CHECK ('
            || 'tenant_code=current_setting(''app.tenant_code'',true) OR current_setting(''app.system_worker'',true)=''true'')', 'isolate_'||t,t);
    END LOOP;
END $$;
