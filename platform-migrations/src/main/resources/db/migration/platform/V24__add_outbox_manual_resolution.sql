ALTER TABLE platform_outbox_event
    ADD COLUMN resolution_reason VARCHAR(1000),
    ADD COLUMN resolution_method VARCHAR(40),
    ADD COLUMN resolved_by VARCHAR(160),
    ADD COLUMN resolved_at TIMESTAMPTZ;

ALTER TABLE platform_outbox_event
    DROP CONSTRAINT ck_platform_outbox_status;

ALTER TABLE platform_outbox_event
    ADD CONSTRAINT ck_platform_outbox_status
        CHECK (status IN (
            'QUEUED', 'PROCESSING', 'RETRY', 'DELIVERED',
            'DEAD_LETTER', 'IGNORED', 'RESOLVED_MANUALLY', 'FAILED'
        ));

CREATE INDEX idx_platform_outbox_resolution
    ON platform_outbox_event (event_type, resolved_at DESC, id DESC)
    WHERE status IN ('IGNORED', 'RESOLVED_MANUALLY');
