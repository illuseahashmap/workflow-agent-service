package io.github.illuseahashmap.workflow.event;

import io.github.illuseahashmap.workflow.shared.event.IntegrationEventEnvelope;
import io.github.illuseahashmap.workflow.shared.event.ReliableEventPublisher;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL Outbox adapter. The caller's transaction owns atomicity with its aggregate change. */
@Repository
public class JdbcReliableEventPublisher implements ReliableEventPublisher {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final List<OutboxCommitNotifier> commitNotifiers;

    public JdbcReliableEventPublisher(
            NamedParameterJdbcTemplate jdbcTemplate,
            List<OutboxCommitNotifier> commitNotifiers
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.commitNotifiers = List.copyOf(commitNotifiers);
    }

    @Override
    public void publish(IntegrationEventEnvelope event) {
        var occurredAt = event.occurredAt().atOffset(ZoneOffset.UTC);
        int inserted = jdbcTemplate.update("""
                        INSERT INTO platform_outbox_event (
                            event_id, event_type, aggregate_type, aggregate_id,
                            tenant_code, trace_id, payload, status, attempt_count,
                            next_attempt_at, created_at, updated_at
                        ) VALUES (
                            :eventId, :eventType, :aggregateType, :aggregateId,
                            :tenantCode, :traceId, CAST(:payloadJson AS jsonb), 'QUEUED', 0,
                            :nextAttemptAt, :createdAt, :updatedAt
                        )
                        ON CONFLICT (event_id) DO NOTHING
                        """,
                Map.of(
                        "eventId", event.eventId(),
                        "eventType", event.eventType(),
                        "aggregateType", event.aggregateType(),
                        "aggregateId", event.aggregateId(),
                        "tenantCode", event.tenantCode(),
                        "traceId", event.traceId(),
                        "payloadJson", event.payloadJson(),
                        "nextAttemptAt", occurredAt,
                        "createdAt", occurredAt,
                        "updatedAt", occurredAt));
        if (inserted == 1) {
            notifyAfterCommit(event.eventType());
        }
    }

    private void notifyAfterCommit(String eventType) {
        Runnable notification = () -> commitNotifiers.forEach(notifier -> notifier.committed(eventType));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notification.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notification.run();
            }
        });
    }
}
