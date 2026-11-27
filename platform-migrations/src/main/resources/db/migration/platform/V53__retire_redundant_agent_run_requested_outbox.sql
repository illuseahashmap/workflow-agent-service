-- AgentRun itself is the durable execution queue. The historical requested event had no consumer
-- and duplicated that source of truth, so retire outstanding records without deleting audit data.
UPDATE platform_outbox_event
SET status = 'DELIVERED',
    delivered_at = COALESCE(delivered_at, CURRENT_TIMESTAMP),
    last_error = 'RETIRED_REDUNDANT_EVENT: agent_run is the durable execution queue',
    claimed_by = NULL,
    claimed_at = NULL,
    claim_expires_at = NULL,
    updated_at = CURRENT_TIMESTAMP
WHERE event_type = 'AgentRunRequested.v1'
  AND status IN ('QUEUED', 'PROCESSING', 'RETRY', 'FAILED');
