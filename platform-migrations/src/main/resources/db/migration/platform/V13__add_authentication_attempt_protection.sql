CREATE TABLE auth_attempt_guard (
    operation VARCHAR(32) NOT NULL,
    bucket_type VARCHAR(16) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    failure_count INTEGER NOT NULL DEFAULT 0,
    last_failed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    blocked_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (operation, bucket_type, fingerprint),
    CONSTRAINT ck_auth_attempt_guard_bucket CHECK (bucket_type IN ('ACCOUNT', 'SOURCE')),
    CONSTRAINT ck_auth_attempt_guard_failure_count CHECK (failure_count >= 0)
);

CREATE INDEX idx_auth_attempt_guard_blocked_until
    ON auth_attempt_guard (blocked_until)
    WHERE blocked_until IS NOT NULL;
