CREATE INDEX IF NOT EXISTS idx_agent_run_tenant_process_created
    ON agent_run (tenant_code, process_instance_id, created_at ASC, id ASC)
    WHERE process_instance_id IS NOT NULL;
