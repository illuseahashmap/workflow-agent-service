package io.github.illuseahashmap.workflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.process.application.AgentCompletionContractException;
import io.github.illuseahashmap.workflow.process.application.AgentCompletionRecoveryService;
import io.github.illuseahashmap.workflow.process.application.dto.AgentCompletionCommand;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Local transport adapter: validates the event envelope and owns Inbox/Outbox delivery state. */
@Component
public class AgentFlowableCompletionProcessor {

    private static final String CONSUMER_NAME = "workflow-agent-completion-v1";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AgentCompletionRecoveryService recoveryService;
    private final ObjectMapper objectMapper;

    public AgentFlowableCompletionProcessor(
            NamedParameterJdbcTemplate jdbcTemplate,
            AgentCompletionRecoveryService recoveryService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.recoveryService = recoveryService;
        this.objectMapper = objectMapper;
    }

    public void process(UUID eventId, String workerId) {
        CompletionEvent event = lockEvent(eventId, workerId);
        if (event == null) {
            return;
        }
        if (inboxCompleted(eventId)) {
            markDelivered(eventId, workerId);
            return;
        }
        insertInbox(event);
        recoveryService.recover(new AgentCompletionCommand(
                event.tenantCode(), event.runId(), event.attemptId(),
                event.activityActivationId(), event.traceId()));
        completeInbox(eventId);
        markDelivered(eventId, workerId);
    }

    private CompletionEvent lockEvent(UUID eventId, String workerId) {
        RawCompletionEvent raw = jdbcTemplate.query("""
                        SELECT event_id, tenant_code, trace_id, payload::text AS payload
                        FROM platform_outbox_event
                        WHERE event_id = :eventId AND event_type = :eventType
                          AND status = 'PROCESSING' AND claimed_by = :workerId
                        FOR UPDATE
                        """, Map.of("eventId", eventId,
                        "eventType", AgentCompletionEventStore.EVENT_TYPE, "workerId", workerId),
                (rs, rowNum) -> new RawCompletionEvent(
                        rs.getObject("event_id", UUID.class), rs.getString("tenant_code"),
                        rs.getString("trace_id"), rs.getString("payload")))
                .stream().findFirst().orElse(null);
        if (raw == null) {
            return null;
        }
        try {
            JsonNode payload = objectMapper.readTree(raw.payload());
            if (!payload.path("runId").canConvertToLong()
                    || !payload.path("attemptId").canConvertToLong()
                    || payload.path("activityActivationId").asText().isBlank()) {
                throw new IllegalArgumentException("Required completion fields are missing");
            }
            return new CompletionEvent(raw.eventId(), raw.tenantCode(),
                    payload.path("runId").asLong(), payload.path("attemptId").asLong(),
                    payload.path("activityActivationId").asText(), raw.traceId());
        } catch (Exception exception) {
            throw new AgentCompletionContractException("Invalid Agent completion event payload", exception);
        }
    }

    private boolean inboxCompleted(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM platform_inbox_event
                WHERE consumer_name = :consumerName AND event_id = :eventId
                  AND completed_at IS NOT NULL
                """, Map.of("consumerName", CONSUMER_NAME, "eventId", eventId), Integer.class);
        return count != null && count > 0;
    }

    private void insertInbox(CompletionEvent event) {
        jdbcTemplate.update("""
                INSERT INTO platform_inbox_event (event_id, consumer_name, tenant_code, received_at)
                VALUES (:eventId, :consumerName, :tenantCode, CURRENT_TIMESTAMP)
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """, Map.of("eventId", event.eventId(), "consumerName", CONSUMER_NAME,
                "tenantCode", event.tenantCode()));
    }

    private void completeInbox(UUID eventId) {
        jdbcTemplate.update("""
                UPDATE platform_inbox_event SET completed_at = CURRENT_TIMESTAMP
                WHERE consumer_name = :consumerName AND event_id = :eventId
                """, Map.of("consumerName", CONSUMER_NAME, "eventId", eventId));
    }

    private void markDelivered(UUID eventId, String workerId) {
        jdbcTemplate.update("""
                UPDATE platform_outbox_event
                SET status = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP,
                    claimed_by = NULL, claimed_at = NULL, claim_expires_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE event_id = :eventId AND status = 'PROCESSING' AND claimed_by = :workerId
                """, Map.of("eventId", eventId, "workerId", workerId));
    }

    private record RawCompletionEvent(UUID eventId, String tenantCode, String traceId, String payload) {
    }

    private record CompletionEvent(
            UUID eventId, String tenantCode, long runId, long attemptId,
            String activityActivationId, String traceId) {
    }
}
