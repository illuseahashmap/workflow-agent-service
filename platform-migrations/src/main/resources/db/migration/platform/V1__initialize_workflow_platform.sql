CREATE TABLE IF NOT EXISTS workflow_tenant (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    tenant_name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    enabled SMALLINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workflow_tenant_id UNIQUE (tenant_id),
    CONSTRAINT uk_workflow_tenant_code UNIQUE (tenant_code)
);

CREATE TABLE IF NOT EXISTS workflow_active_version (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    process_definition_key VARCHAR(128) NOT NULL,
    process_definition_id VARCHAR(128) NOT NULL,
    version INTEGER NOT NULL,
    activated_by VARCHAR(128),
    activated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workflow_active_version UNIQUE (tenant_id, process_definition_key)
);

CREATE TABLE IF NOT EXISTS workflow_service_client (
    id BIGSERIAL PRIMARY KEY,
    client_code VARCHAR(64) NOT NULL,
    client_name VARCHAR(128) NOT NULL,
    secret_key_ref VARCHAR(256),
    secret_ciphertext TEXT,
    secret_version INTEGER NOT NULL DEFAULT 1,
    allowed_tenant_codes TEXT NOT NULL DEFAULT '*',
    allowed_paths TEXT NOT NULL DEFAULT '*',
    enabled SMALLINT NOT NULL DEFAULT 1,
    expires_at TIMESTAMPTZ,
    description VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workflow_service_client_code UNIQUE (client_code)
);

ALTER TABLE workflow_service_client ADD COLUMN IF NOT EXISTS secret_key_ref VARCHAR(256);
ALTER TABLE workflow_service_client ADD COLUMN IF NOT EXISTS secret_ciphertext TEXT;
ALTER TABLE workflow_service_client ADD COLUMN IF NOT EXISTS secret_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE workflow_service_client ADD COLUMN IF NOT EXISTS allowed_tenant_codes TEXT NOT NULL DEFAULT '*';
ALTER TABLE workflow_service_client ADD COLUMN IF NOT EXISTS allowed_paths TEXT NOT NULL DEFAULT '*';
ALTER TABLE workflow_service_client ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS workflow_service_token_nonce (
    id BIGSERIAL PRIMARY KEY,
    client_code VARCHAR(64) NOT NULL,
    nonce VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workflow_service_token_nonce UNIQUE (client_code, nonce)
);

CREATE INDEX IF NOT EXISTS idx_workflow_service_token_nonce_expires
    ON workflow_service_token_nonce (expires_at);

CREATE TABLE IF NOT EXISTS workflow_node_assignment_rule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    process_definition_id VARCHAR(128) NOT NULL,
    process_definition_key VARCHAR(128) NOT NULL,
    version INTEGER NOT NULL,
    task_definition_key VARCHAR(128) NOT NULL,
    priority INTEGER NOT NULL DEFAULT 100,
    assignment_type VARCHAR(32) NOT NULL,
    empty_user_strategy VARCHAR(32),
    enabled SMALLINT NOT NULL DEFAULT 1,
    description VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_workflow_assignment_rule_lookup
    ON workflow_node_assignment_rule (tenant_id, process_definition_id, task_definition_key, enabled, priority);

CREATE TABLE IF NOT EXISTS workflow_assignment_target (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    rule_id BIGINT NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_value VARCHAR(256) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_workflow_assignment_target_rule
        FOREIGN KEY (rule_id) REFERENCES workflow_node_assignment_rule (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_workflow_assignment_target_rule
    ON workflow_assignment_target (rule_id, sort_order);

CREATE TABLE IF NOT EXISTS workflow_node_assignment_rule_condition (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    rule_id BIGINT NOT NULL,
    variable_name VARCHAR(128) NOT NULL,
    operator VARCHAR(32) NOT NULL,
    variable_value VARCHAR(512),
    sort_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_workflow_assignment_condition_rule
        FOREIGN KEY (rule_id) REFERENCES workflow_node_assignment_rule (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_workflow_assignment_condition_rule
    ON workflow_node_assignment_rule_condition (rule_id, sort_order);

INSERT INTO workflow_tenant (tenant_id, tenant_code, tenant_name, description)
VALUES ('default', 'default', 'Default Tenant', 'Local development tenant')
ON CONFLICT (tenant_code) DO NOTHING;

