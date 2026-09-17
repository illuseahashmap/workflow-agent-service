CREATE TABLE agent_provider (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    provider_code VARCHAR(64) NOT NULL,
    provider_name VARCHAR(128) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    base_url VARCHAR(512),
    default_model VARCHAR(128),
    enabled SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_provider_code UNIQUE (tenant_code, provider_code),
    CONSTRAINT uk_agent_provider_id_tenant UNIQUE (id, tenant_code),
    CONSTRAINT ck_agent_provider_type CHECK (provider_type IN ('MOCK', 'OPENAI_COMPATIBLE')),
    CONSTRAINT ck_agent_provider_enabled CHECK (enabled IN (0, 1))
);

CREATE TABLE agent_credential (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    provider_id BIGINT NOT NULL,
    secret_ciphertext TEXT NOT NULL,
    secret_hint VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_credential_provider UNIQUE (tenant_code, provider_id),
    CONSTRAINT fk_agent_credential_provider FOREIGN KEY (provider_id, tenant_code)
        REFERENCES agent_provider (id, tenant_code) ON DELETE CASCADE
);

CREATE TABLE agent_definition (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    agent_code VARCHAR(64) NOT NULL,
    agent_name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    enabled SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_definition_code UNIQUE (tenant_code, agent_code),
    CONSTRAINT uk_agent_definition_id_tenant UNIQUE (id, tenant_code),
    CONSTRAINT ck_agent_definition_enabled CHECK (enabled IN (0, 1))
);

CREATE TABLE agent_definition_version (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    definition_id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    provider_id BIGINT,
    model_name VARCHAR(128),
    system_prompt TEXT NOT NULL DEFAULT '',
    timeout_seconds INTEGER NOT NULL DEFAULT 120,
    failure_policy VARCHAR(32) NOT NULL DEFAULT 'FAIL_PROCESS',
    output_schema TEXT,
    created_by VARCHAR(64),
    published_by VARCHAR(64),
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_definition_version UNIQUE (tenant_code, definition_id, version),
    CONSTRAINT uk_agent_version_id_tenant UNIQUE (id, tenant_code),
    CONSTRAINT fk_agent_version_definition FOREIGN KEY (definition_id, tenant_code)
        REFERENCES agent_definition (id, tenant_code) ON DELETE CASCADE,
    CONSTRAINT fk_agent_version_provider FOREIGN KEY (provider_id, tenant_code)
        REFERENCES agent_provider (id, tenant_code),
    CONSTRAINT ck_agent_version_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_agent_version_timeout CHECK (timeout_seconds BETWEEN 1 AND 3600),
    CONSTRAINT ck_agent_version_failure_policy CHECK (failure_policy IN ('FAIL_PROCESS', 'CONTINUE_EMPTY', 'MANUAL_REVIEW'))
);

CREATE TABLE agent_run (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    agent_version_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    process_instance_id VARCHAR(128),
    execution_id VARCHAR(128),
    activity_id VARCHAR(128),
    activity_activation_id VARCHAR(128),
    current_attempt_id BIGINT,
    deadline_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    result_status VARCHAR(16),
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_run_idempotency UNIQUE (tenant_code, idempotency_key),
    CONSTRAINT uk_agent_run_id_tenant UNIQUE (id, tenant_code),
    CONSTRAINT fk_agent_run_version FOREIGN KEY (agent_version_id, tenant_code)
        REFERENCES agent_definition_version (id, tenant_code),
    CONSTRAINT ck_agent_run_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')),
    CONSTRAINT ck_agent_run_result_status CHECK (
        result_status IS NULL OR result_status IN ('SUCCESS', 'EMPTY', 'PARTIAL', 'REJECTED', 'FAILED')
    )
);

CREATE TABLE agent_run_attempt (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    agent_run_id BIGINT NOT NULL,
    attempt_no INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_run_attempt UNIQUE (tenant_code, agent_run_id, attempt_no),
    CONSTRAINT uk_agent_attempt_id_tenant UNIQUE (id, tenant_code),
    CONSTRAINT uk_agent_attempt_run UNIQUE (id, tenant_code, agent_run_id),
    CONSTRAINT fk_agent_attempt_run FOREIGN KEY (agent_run_id, tenant_code)
        REFERENCES agent_run (id, tenant_code) ON DELETE CASCADE,
    CONSTRAINT ck_agent_attempt_number CHECK (attempt_no > 0),
    CONSTRAINT ck_agent_attempt_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
    )
);

CREATE TABLE agent_run_step (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    agent_run_id BIGINT NOT NULL,
    attempt_id BIGINT NOT NULL,
    sequence_no INTEGER NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_run_step UNIQUE (tenant_code, agent_run_id, attempt_id, sequence_no),
    CONSTRAINT fk_agent_step_run FOREIGN KEY (agent_run_id, tenant_code)
        REFERENCES agent_run (id, tenant_code) ON DELETE CASCADE,
    CONSTRAINT fk_agent_step_attempt FOREIGN KEY (attempt_id, tenant_code, agent_run_id)
        REFERENCES agent_run_attempt (id, tenant_code, agent_run_id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_step_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_agent_step_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED'))
);

CREATE TABLE agent_run_checkpoint (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    agent_run_id BIGINT NOT NULL,
    attempt_id BIGINT NOT NULL,
    sequence_no INTEGER NOT NULL,
    checkpoint_type VARCHAR(32) NOT NULL,
    snapshot_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_run_checkpoint UNIQUE (tenant_code, agent_run_id, attempt_id, sequence_no),
    CONSTRAINT fk_agent_checkpoint_run FOREIGN KEY (agent_run_id, tenant_code)
        REFERENCES agent_run (id, tenant_code) ON DELETE CASCADE,
    CONSTRAINT fk_agent_checkpoint_attempt FOREIGN KEY (attempt_id, tenant_code, agent_run_id)
        REFERENCES agent_run_attempt (id, tenant_code, agent_run_id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_checkpoint_sequence CHECK (sequence_no > 0)
);

CREATE TABLE agent_run_state_history (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    agent_run_id BIGINT NOT NULL,
    attempt_id BIGINT,
    old_status VARCHAR(16) NOT NULL,
    new_status VARCHAR(16) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    operator_type VARCHAR(16) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_history_run FOREIGN KEY (agent_run_id, tenant_code)
        REFERENCES agent_run (id, tenant_code) ON DELETE CASCADE,
    CONSTRAINT fk_agent_history_attempt FOREIGN KEY (attempt_id, tenant_code, agent_run_id)
        REFERENCES agent_run_attempt (id, tenant_code, agent_run_id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_history_old_status CHECK (
        old_status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
    ),
    CONSTRAINT ck_agent_history_new_status CHECK (
        new_status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
    ),
    CONSTRAINT ck_agent_history_operator CHECK (operator_type IN ('SYSTEM', 'WORKER', 'USER'))
);

ALTER TABLE agent_run
    ADD CONSTRAINT fk_agent_run_current_attempt FOREIGN KEY (current_attempt_id, tenant_code, id)
    REFERENCES agent_run_attempt (id, tenant_code, agent_run_id) DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_agent_definition_tenant_updated ON agent_definition (tenant_code, updated_at DESC);
CREATE INDEX idx_agent_version_definition ON agent_definition_version (tenant_code, definition_id, version DESC);
CREATE INDEX idx_agent_provider_tenant_updated ON agent_provider (tenant_code, updated_at DESC);
CREATE INDEX idx_agent_run_tenant_created ON agent_run (tenant_code, created_at DESC);
CREATE INDEX idx_agent_run_claim ON agent_run (status, available_at, lease_expires_at);

INSERT INTO auth_permission (permission_code, permission_name, description, scope) VALUES
    ('agent:manage', 'Agent 管理', '管理当前租户的 Agent、版本、Provider 和凭证', 'TENANT'),
    ('agent:run:read', 'Agent 运行记录', '查看当前租户的 Agent 运行与审计记录', 'TENANT')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO auth_role_permission (tenant_code, role_code, permission_code)
SELECT role.tenant_code, role.role_code, permission.permission_code
FROM auth_role role
CROSS JOIN (VALUES ('agent:manage'), ('agent:run:read')) permission(permission_code)
WHERE role.role_code = 'TENANT_ADMIN'
ON CONFLICT (tenant_code, role_code, permission_code) DO NOTHING;

INSERT INTO auth_role_permission (tenant_code, role_code, permission_code)
VALUES ('*', 'PLATFORM_ADMIN', 'agent:manage'), ('*', 'PLATFORM_ADMIN', 'agent:run:read')
ON CONFLICT (tenant_code, role_code, permission_code) DO NOTHING;
