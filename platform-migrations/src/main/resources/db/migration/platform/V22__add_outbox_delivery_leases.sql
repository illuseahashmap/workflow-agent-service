ALTER TABLE platform_outbox_event
    ADD COLUMN claimed_by VARCHAR(160),
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN claim_expires_at TIMESTAMPTZ,
    ADD COLUMN dead_lettered_at TIMESTAMPTZ;

UPDATE platform_outbox_event
SET status = 'RETRY', next_attempt_at = CURRENT_TIMESTAMP,
    last_error = CONCAT('RECOVERED_DURING_V22: ', COALESCE(last_error, '')),
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'PROCESSING';

UPDATE platform_outbox_event
SET status = 'DEAD_LETTER', dead_lettered_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE status = 'FAILED';

ALTER TABLE platform_outbox_event
    DROP CONSTRAINT ck_platform_outbox_status;

ALTER TABLE platform_outbox_event
    ADD CONSTRAINT ck_platform_outbox_status
        CHECK (status IN ('QUEUED', 'PROCESSING', 'RETRY', 'DELIVERED', 'DEAD_LETTER', 'FAILED'));

DROP INDEX idx_platform_outbox_dispatch;
CREATE INDEX idx_platform_outbox_dispatch
    ON platform_outbox_event (event_type, status, next_attempt_at, claim_expires_at, id);

CREATE INDEX idx_platform_outbox_dead_letter
    ON platform_outbox_event (event_type, dead_lettered_at DESC, id DESC)
    WHERE status = 'DEAD_LETTER';
