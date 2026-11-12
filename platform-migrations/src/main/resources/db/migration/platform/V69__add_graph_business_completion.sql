-- Existing graph reservations cannot retroactively prove their workflow binding.
-- They remain visible but cannot be accepted; cancel and submit a fresh Run.
ALTER TABLE agent_graph_execution ADD COLUMN business_identity JSONB;
ALTER TABLE agent_graph_execution ADD COLUMN attempt_id BIGINT;
ALTER TABLE agent_graph_execution ADD CONSTRAINT fk_graph_business_attempt
    FOREIGN KEY (attempt_id,tenant_code,run_id) REFERENCES agent_run_attempt(id,tenant_code,agent_run_id);
ALTER TABLE agent_graph_dispatch ADD COLUMN completion_status VARCHAR(16)
    CHECK (completion_status IN ('COMMITTED','DISCARDED','BLOCKED'));
ALTER TABLE agent_graph_dispatch ADD COLUMN completion_reason VARCHAR(64);
ALTER TABLE agent_graph_dispatch ADD COLUMN completed_at TIMESTAMPTZ;
ALTER TABLE agent_graph_dispatch ADD COLUMN completion_failures INTEGER NOT NULL DEFAULT 0 CHECK (completion_failures>=0);
ALTER TABLE agent_graph_dispatch ADD CONSTRAINT ck_graph_completion_terminal
    CHECK ((completion_status IS NULL)=(completed_at IS NULL)
        AND (completion_status IS NULL OR (status='ACKNOWLEDGED' AND completion_reason IS NOT NULL)));
CREATE INDEX idx_graph_completion_pending ON agent_graph_dispatch(available_at,run_id)
    WHERE status='ACKNOWLEDGED' AND completion_status IS NULL;

CREATE FUNCTION guard_graph_business_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.business_identity IS DISTINCT FROM OLD.business_identity
       OR (OLD.attempt_id IS NOT NULL AND NEW.attempt_id IS DISTINCT FROM OLD.attempt_id) THEN
        RAISE EXCEPTION 'Graph business binding is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER graph_business_binding_immutable BEFORE UPDATE ON agent_graph_execution
    FOR EACH ROW EXECUTE FUNCTION guard_graph_business_binding();

CREATE FUNCTION guard_graph_completion_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status='ACKNOWLEDGED' AND ROW(NEW.command_id,NEW.status,NEW.observation_json)
        IS DISTINCT FROM ROW(OLD.command_id,OLD.status,OLD.observation_json) THEN
        RAISE EXCEPTION 'Acknowledged graph observation is immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.completion_status IS NOT NULL AND ROW(NEW.completion_status,NEW.completion_reason,NEW.completed_at)
        IS DISTINCT FROM ROW(OLD.completion_status,OLD.completion_reason,OLD.completed_at) THEN
        RAISE EXCEPTION 'Graph completion record is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER graph_completion_immutable BEFORE UPDATE ON agent_graph_dispatch
    FOR EACH ROW EXECUTE FUNCTION guard_graph_completion_record();
