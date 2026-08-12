package io.github.illuseahashmap.workflow.agent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns delivery state and leases for the local Agent completion transport. */
@Repository
public class AgentCompletionEventStore {

    static final String EVENT_TYPE = "AgentRunCompleted.v1";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public AgentCompletionEventStore(
            NamedParameterJdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public List<UUID> claim(String workerId, int limit, Duration lease) {
        return transactionTemplate.execute(status -> jdbcTemplate.queryForList("""
                WITH candidates AS (
                    SELECT id
                    FROM platform_outbox_event
                    WHERE event_type = :eventType
                      AND ((status IN ('QUEUED', 'RETRY') AND next_attempt_at <= CURRENT_TIMESTAMP)
                        OR (status = 'PROCESSING' AND claim_expires_at <= CURRENT_TIMESTAMP))
                    ORDER BY id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE platform_outbox_event event
                SET status = 'PROCESSING', claimed_by = :workerId,
                    claimed_at = CURRENT_TIMESTAMP,
                    claim_expires_at = CURRENT_TIMESTAMP + (:leaseSeconds * INTERVAL '1 second'),
                    attempt_count = attempt_count + 1,
                    updated_at = CURRENT_TIMESTAMP
                FROM candidates
                WHERE event.id = candidates.id
                RETURNING event.event_id
                """, Map.of(
                "eventType", EVENT_TYPE,
                "workerId", workerId,
                "leaseSeconds", lease.toSeconds(),
                "limit", limit), UUID.class));
    }

    public void retryOrDeadLetter(
            UUID eventId, String workerId, Throwable failure, int maxAttempts, boolean permanent
    ) {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update("""
                UPDATE platform_outbox_event
                SET status = CASE WHEN :permanent OR attempt_count >= :maxAttempts
                        THEN 'DEAD_LETTER' ELSE 'RETRY' END,
                    next_attempt_at = CASE WHEN :permanent OR attempt_count >= :maxAttempts THEN next_attempt_at
                        ELSE CURRENT_TIMESTAMP
                            + (LEAST(300, POWER(2, LEAST(attempt_count, 8))) * INTERVAL '1 second') END,
                    last_error = :lastError,
                    dead_lettered_at = CASE WHEN :permanent OR attempt_count >= :maxAttempts
                        THEN CURRENT_TIMESTAMP ELSE NULL END,
                    claimed_by = NULL, claimed_at = NULL, claim_expires_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE event_id = :eventId AND status = 'PROCESSING' AND claimed_by = :workerId
                """, Map.of(
                "eventId", eventId,
                "workerId", workerId,
                "permanent", permanent,
                "maxAttempts", maxAttempts,
                "lastError", summarize(failure))));
    }

    public void release(UUID eventId, String workerId, String reason) {
        transactionTemplate.executeWithoutResult(status -> jdbcTemplate.update("""
                UPDATE platform_outbox_event
                SET status = 'RETRY', next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 second',
                    last_error = :reason, claimed_by = NULL, claimed_at = NULL,
                    claim_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE event_id = :eventId AND status = 'PROCESSING' AND claimed_by = :workerId
                """, Map.of("eventId", eventId, "workerId", workerId, "reason", reason)));
    }

    public List<DeadLetterEvent> deadLetters(int limit) {
        return jdbcTemplate.query("""
                SELECT event_id, tenant_code, aggregate_id, attempt_count, last_error,
                       created_at, dead_lettered_at
                FROM platform_outbox_event
                WHERE event_type = :eventType AND status = 'DEAD_LETTER'
                ORDER BY dead_lettered_at DESC, id DESC
                LIMIT :limit
                """, Map.of("eventType", EVENT_TYPE, "limit", limit), (rs, rowNum) -> new DeadLetterEvent(
                rs.getObject("event_id", UUID.class), rs.getString("tenant_code"),
                rs.getString("aggregate_id"), rs.getInt("attempt_count"),
                rs.getString("last_error"), rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("dead_lettered_at", java.time.OffsetDateTime.class)));
    }

    public boolean replay(UUID eventId) {
        return jdbcTemplate.update("""
                UPDATE platform_outbox_event
                SET status = 'RETRY', attempt_count = 0, next_attempt_at = CURRENT_TIMESTAMP,
                    last_error = NULL, dead_lettered_at = NULL,
                    claimed_by = NULL, claimed_at = NULL, claim_expires_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE event_id = :eventId AND event_type = :eventType AND status = 'DEAD_LETTER'
                """, Map.of("eventId", eventId, "eventType", EVENT_TYPE)) == 1;
    }

    public boolean ignore(UUID eventId) {
        return jdbcTemplate.update("""
                UPDATE platform_outbox_event
                SET status = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP,
                    last_error = CONCAT('IGNORED_BY_OPERATOR: ', COALESCE(last_error, '')),
                    claimed_by = NULL, claimed_at = NULL, claim_expires_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE event_id = :eventId AND event_type = :eventType AND status = 'DEAD_LETTER'
                """, Map.of("eventId", eventId, "eventType", EVENT_TYPE)) == 1;
    }

    private String summarize(Throwable failure) {
        String message = failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "No message" : failure.getMessage());
        return message.substring(0, Math.min(message.length(), 1000));
    }

    public record DeadLetterEvent(
            UUID eventId,
            String tenantCode,
            String aggregateId,
            int attemptCount,
            String lastError,
            java.time.OffsetDateTime createdAt,
            java.time.OffsetDateTime deadLetteredAt
    ) {
    }
}
