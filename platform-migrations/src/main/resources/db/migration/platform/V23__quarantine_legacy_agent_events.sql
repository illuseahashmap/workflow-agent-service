UPDATE platform_outbox_event
SET status = 'DEAD_LETTER',
    last_error = 'LEGACY_COMPLETION_EVENT_REQUIRES_MANUAL_RECONCILIATION',
    dead_lettered_at = CURRENT_TIMESTAMP,
    claimed_by = NULL,
    claimed_at = NULL,
    claim_expires_at = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE event_type = 'AgentRunCompleted'
  AND status NOT IN ('DELIVERED', 'DEAD_LETTER');

UPDATE platform_outbox_event
SET status = 'DELIVERED',
    last_error = 'LEGACY_REQUEST_EVENT_ARCHIVED',
    delivered_at = COALESCE(delivered_at, CURRENT_TIMESTAMP),
    claimed_by = NULL,
    claimed_at = NULL,
    claim_expires_at = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE event_type = 'AgentRunRequested'
  AND status <> 'DELIVERED';
