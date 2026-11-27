ALTER TABLE agent_run
    ADD COLUMN pause_requested_at TIMESTAMPTZ,
    ADD COLUMN paused_at TIMESTAMPTZ,
    ADD COLUMN pause_reason VARCHAR(1000);

ALTER TABLE agent_run DROP CONSTRAINT ck_agent_run_status;
ALTER TABLE agent_run
    ADD CONSTRAINT ck_agent_run_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'PAUSED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'));

ALTER TABLE agent_run_attempt DROP CONSTRAINT ck_agent_attempt_status;
ALTER TABLE agent_run_attempt
    ADD CONSTRAINT ck_agent_attempt_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'PAUSED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'));

ALTER TABLE agent_run_step DROP CONSTRAINT ck_agent_step_status;
ALTER TABLE agent_run_step
    ADD CONSTRAINT ck_agent_step_status
        CHECK (status IN ('PENDING', 'RUNNING', 'PAUSED', 'SUCCEEDED', 'FAILED', 'SKIPPED'));

ALTER TABLE agent_run_state_history DROP CONSTRAINT ck_agent_history_old_status;
ALTER TABLE agent_run_state_history
    ADD CONSTRAINT ck_agent_history_old_status
        CHECK (old_status IN ('QUEUED', 'RUNNING', 'PAUSED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'));

ALTER TABLE agent_run_state_history DROP CONSTRAINT ck_agent_history_new_status;
ALTER TABLE agent_run_state_history
    ADD CONSTRAINT ck_agent_history_new_status
        CHECK (new_status IN ('QUEUED', 'RUNNING', 'PAUSED', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED'));

CREATE INDEX idx_agent_run_pause_requested
    ON agent_run (pause_requested_at, id)
    WHERE status = 'RUNNING' AND pause_requested_at IS NOT NULL;
