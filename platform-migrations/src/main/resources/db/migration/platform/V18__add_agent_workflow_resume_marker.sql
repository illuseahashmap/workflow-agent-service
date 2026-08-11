ALTER TABLE agent_run
    ADD COLUMN workflow_resumed_at TIMESTAMPTZ;

CREATE INDEX idx_agent_run_workflow_resume
    ON agent_run (tenant_code, status, workflow_resumed_at)
    WHERE trigger_type = 'FLOWABLE' AND process_instance_id IS NOT NULL;
