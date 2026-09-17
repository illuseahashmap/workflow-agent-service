CREATE TABLE workflow_operation_audit (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    actor_username VARCHAR(128),
    process_instance_id VARCHAR(64),
    process_definition_key VARCHAR(255),
    task_id VARCHAR(64),
    subject VARCHAR(500),
    previous_state VARCHAR(64),
    next_state VARCHAR(64),
    reason VARCHAR(1000),
    trace_id VARCHAR(128),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_workflow_operation_audit_tenant_time
    ON workflow_operation_audit (tenant_code, occurred_at DESC);
CREATE INDEX idx_workflow_operation_audit_process_time
    ON workflow_operation_audit (tenant_code, process_instance_id, occurred_at DESC);
CREATE INDEX idx_workflow_operation_audit_trace
    ON workflow_operation_audit (trace_id) WHERE trace_id IS NOT NULL;

ALTER TABLE workflow_operation_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_operation_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_workflow_operation_audit ON workflow_operation_audit
    USING (
        current_setting('app.platform_admin', true) = 'true'
        OR current_setting('app.system_worker', true) = 'true'
        OR tenant_code = current_setting('app.tenant_code', true)
    )
    WITH CHECK (
        current_setting('app.platform_admin', true) = 'true'
        OR current_setting('app.system_worker', true) = 'true'
        OR tenant_code = current_setting('app.tenant_code', true)
    );
