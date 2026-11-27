CREATE TABLE platform_outbox_event (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    aggregate_type VARCHAR(120) NOT NULL,
    aggregate_id VARCHAR(120) NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    CONSTRAINT uk_platform_outbox_event_id UNIQUE (event_id),
    CONSTRAINT ck_platform_outbox_status CHECK (status IN ('QUEUED', 'PROCESSING', 'DELIVERED', 'FAILED')),
    CONSTRAINT ck_platform_outbox_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_platform_outbox_dispatch
    ON platform_outbox_event (status, next_attempt_at, id);
CREATE INDEX idx_platform_outbox_tenant
    ON platform_outbox_event (tenant_code, created_at DESC);

CREATE TABLE platform_inbox_event (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL,
    consumer_name VARCHAR(160) NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_platform_inbox_consumer_event UNIQUE (consumer_name, event_id)
);

CREATE INDEX idx_platform_inbox_tenant
    ON platform_inbox_event (tenant_code, received_at DESC);

ALTER TABLE platform_outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_outbox_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_platform_outbox_event ON platform_outbox_event
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

ALTER TABLE platform_inbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_inbox_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation_platform_inbox_event ON platform_inbox_event
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
