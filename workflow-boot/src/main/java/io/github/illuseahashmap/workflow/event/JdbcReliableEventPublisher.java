package io.github.illuseahashmap.workflow.event;

import io.github.illuseahashmap.workflow.shared.event.IntegrationEventEnvelope;
import io.github.illuseahashmap.workflow.shared.event.ReliableEventPublisher;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL Outbox adapter. The caller's transaction owns atomicity with its aggregate change. */
@Repository
public class JdbcReliableEventPublisher implements ReliableEventPublisher {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcReliableEventPublisher(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void publish(IntegrationEventEnvelope event) {
        var occurredAt = event.occurredAt().atOffset(ZoneOffset.UTC);
        jdbcTemplate.update("""
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
    }
}
