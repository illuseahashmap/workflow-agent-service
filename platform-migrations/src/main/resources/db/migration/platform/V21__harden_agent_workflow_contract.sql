ALTER TABLE agent_definition_version
    ADD COLUMN execution_mode VARCHAR(32) NOT NULL DEFAULT 'MODEL_ONLY';

ALTER TABLE agent_definition_version
    ADD CONSTRAINT ck_agent_version_execution_mode
    CHECK (execution_mode IN ('MODEL_ONLY', 'PLATFORM_AGENT', 'REMOTE_AGENT'));

ALTER TABLE agent_run
    ADD COLUMN output_mapping_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN process_failure_policy VARCHAR(32) NOT NULL DEFAULT 'HOLD_FOR_OPERATIONS',
    ADD COLUMN process_wait_timeout_seconds INTEGER;

ALTER TABLE agent_run
    ADD CONSTRAINT ck_agent_run_process_failure_policy
    CHECK (process_failure_policy IN ('CONTINUE_EMPTY', 'MANUAL_REVIEW', 'HOLD_FOR_OPERATIONS')),
    ADD CONSTRAINT ck_agent_run_process_wait_timeout
    CHECK (process_wait_timeout_seconds IS NULL OR process_wait_timeout_seconds BETWEEN 1 AND 3600);

CREATE UNIQUE INDEX uk_agent_run_activity_activation
    ON agent_run (
        tenant_code, process_instance_id, execution_id, activity_id,
        activity_activation_id, agent_version_id
    )
    WHERE trigger_type = 'FLOWABLE' AND activity_activation_id IS NOT NULL;
