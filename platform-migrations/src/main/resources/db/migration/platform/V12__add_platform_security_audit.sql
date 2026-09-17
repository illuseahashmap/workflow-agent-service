CREATE TABLE platform_security_audit (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64),
    tenant_code VARCHAR(64),
    source_address VARCHAR(128),
    subject VARCHAR(255),
    outcome VARCHAR(32) NOT NULL,
    details VARCHAR(1000),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_platform_security_audit_event_time
    ON platform_security_audit (event_type, occurred_at DESC);

CREATE INDEX idx_platform_security_audit_tenant_time
    ON platform_security_audit (tenant_code, occurred_at DESC);
