CREATE TABLE workflow_assignment_fallback_command (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    process_instance_id VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workflow_assignment_fallback_task UNIQUE (task_id),
    CONSTRAINT ck_workflow_assignment_fallback_action
        CHECK (action IN ('AUTO_COMPLETE', 'AUTO_REJECT')),
    CONSTRAINT ck_workflow_assignment_fallback_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_workflow_assignment_fallback_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_workflow_assignment_fallback_due
    ON workflow_assignment_fallback_command (status, next_attempt_at, id);

CREATE INDEX idx_workflow_assignment_fallback_processing
    ON workflow_assignment_fallback_command (status, claimed_at)
    WHERE status = 'PROCESSING';
