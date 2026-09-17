CREATE TABLE agent_recovery_decision (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    agent_run_id BIGINT NOT NULL,
    attempt_id BIGINT NOT NULL,
    step_id BIGINT NOT NULL,
    error_code VARCHAR(64) NOT NULL,
    failure_category VARCHAR(32) NOT NULL,
    action VARCHAR(32) NOT NULL,
    retry_scheduled BOOLEAN NOT NULL,
    requires_human_review BOOLEAN NOT NULL,
    result_status VARCHAR(16),
    reason VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_recovery_decision_id_tenant UNIQUE (id, tenant_code),
    CONSTRAINT fk_agent_recovery_run FOREIGN KEY (agent_run_id, tenant_code)
        REFERENCES agent_run (id, tenant_code) ON DELETE CASCADE,
    CONSTRAINT fk_agent_recovery_attempt FOREIGN KEY (attempt_id, tenant_code, agent_run_id)
        REFERENCES agent_run_attempt (id, tenant_code, agent_run_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_recovery_step FOREIGN KEY (step_id, tenant_code, agent_run_id, attempt_id)
        REFERENCES agent_run_step (id, tenant_code, agent_run_id, attempt_id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_recovery_result_status CHECK (
        result_status IS NULL OR result_status IN ('SUCCESS', 'EMPTY', 'PARTIAL', 'REJECTED', 'FAILED')
    )
);

CREATE INDEX idx_agent_recovery_decision_run
    ON agent_recovery_decision (tenant_code, agent_run_id, created_at, id);

ALTER TABLE agent_recovery_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_recovery_decision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_agent_recovery_decision ON agent_recovery_decision
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
