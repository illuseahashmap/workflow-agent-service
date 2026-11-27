ALTER TABLE agent_run
    ADD COLUMN trigger_type VARCHAR(32) NOT NULL DEFAULT 'FLOWABLE',
    ADD COLUMN input_snapshot_json JSONB,
    ADD COLUMN output_snapshot_json JSONB,
    ADD COLUMN requested_by VARCHAR(128);

ALTER TABLE agent_run
    ADD CONSTRAINT ck_agent_run_trigger_type
    CHECK (trigger_type IN ('FLOWABLE', 'MANUAL_TEST'));

ALTER TABLE agent_run_step
    ADD CONSTRAINT uk_agent_step_id_run_attempt UNIQUE (id, tenant_code, agent_run_id, attempt_id);

CREATE TABLE agent_model_invocation (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    agent_run_id BIGINT NOT NULL,
    attempt_id BIGINT NOT NULL,
    step_id BIGINT NOT NULL,
    provider_id BIGINT NOT NULL,
    requested_model VARCHAR(128) NOT NULL,
    actual_model VARCHAR(128),
    provider_request_id VARCHAR(256),
    finish_reason VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    reasoning_tokens INTEGER NOT NULL DEFAULT 0,
    latency_ms BIGINT,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_agent_model_invocation_step UNIQUE (tenant_code, step_id),
    CONSTRAINT fk_agent_model_invocation_run FOREIGN KEY (agent_run_id, tenant_code)
        REFERENCES agent_run (id, tenant_code) ON DELETE CASCADE,
    CONSTRAINT fk_agent_model_invocation_attempt FOREIGN KEY (attempt_id, tenant_code, agent_run_id)
        REFERENCES agent_run_attempt (id, tenant_code, agent_run_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_model_invocation_step FOREIGN KEY (step_id, tenant_code, agent_run_id, attempt_id)
        REFERENCES agent_run_step (id, tenant_code, agent_run_id, attempt_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_model_invocation_provider FOREIGN KEY (provider_id, tenant_code)
        REFERENCES agent_provider (id, tenant_code),
    CONSTRAINT ck_agent_model_invocation_status CHECK (status IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_agent_model_invocation_tokens CHECK (
        input_tokens >= 0 AND output_tokens >= 0 AND reasoning_tokens >= 0
    ),
    CONSTRAINT ck_agent_model_invocation_latency CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

CREATE INDEX idx_agent_model_invocation_run
    ON agent_model_invocation (tenant_code, agent_run_id, attempt_id);

INSERT INTO auth_permission (permission_code, permission_name, description, scope)
VALUES ('agent:run:execute', 'Agent 测试运行', '发起当前租户 Agent 的手动测试运行', 'TENANT')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO auth_role_permission (tenant_code, role_code, permission_code)
SELECT role.tenant_code, role.role_code, 'agent:run:execute'
FROM auth_role role
WHERE role.role_code = 'TENANT_ADMIN'
ON CONFLICT (tenant_code, role_code, permission_code) DO NOTHING;

INSERT INTO auth_role_permission (tenant_code, role_code, permission_code)
VALUES ('*', 'PLATFORM_ADMIN', 'agent:run:execute')
ON CONFLICT (tenant_code, role_code, permission_code) DO NOTHING;
