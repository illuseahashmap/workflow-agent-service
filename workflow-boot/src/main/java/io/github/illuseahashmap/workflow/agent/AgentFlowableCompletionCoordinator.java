package io.github.illuseahashmap.workflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.process.application.ProcessVariablePolicy;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.AgentTaskExecutionListener;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.Execution;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Local transport consumer for versioned Agent completion events. Inbox insertion,
 * Flowable recovery and delivery acknowledgement share one transaction.
 */
@Component
public class AgentFlowableCompletionCoordinator {

    private static final String CONSUMER_NAME = "workflow-agent-completion-v1";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RuntimeService runtimeService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public AgentFlowableCompletionCoordinator(
            NamedParameterJdbcTemplate jdbcTemplate,
            RuntimeService runtimeService,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeService = runtimeService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${workflow.agent.workflow-resume-interval-ms:1000}")
    public void resumeCompletedRuns() {
        TrustedDataAccessContext.runAsSystemWorker(() -> {
            List<UUID> eventIds = jdbcTemplate.queryForList("""
                    SELECT event_id
                    FROM platform_outbox_event
                    WHERE event_type = 'AgentRunCompleted.v1'
                      AND status = 'QUEUED'
                      AND next_attempt_at <= CURRENT_TIMESTAMP
                    ORDER BY id
                    LIMIT 50
                    """, Map.of(), UUID.class);
            for (UUID eventId : eventIds) {
                transactionTemplate.executeWithoutResult(status -> consume(eventId));
            }
            return null;
        });
    }

    private void consume(UUID eventId) {
        CompletionEvent event = lockEvent(eventId);
        if (event == null) {
            return;
        }
        if (inboxCompleted(eventId)) {
            markDelivered(eventId);
            return;
        }
        insertInbox(event);
        CompletedRun run = lockRun(event);
        if (run != null && run.processInstanceId() != null) {
            recoverFlowable(run, event);
            markRunHandled(run);
        }
        completeInbox(eventId);
        markDelivered(eventId);
    }

    private CompletionEvent lockEvent(UUID eventId) {
        return jdbcTemplate.query("""
                        SELECT event_id, tenant_code, trace_id, payload
                        FROM platform_outbox_event
                        WHERE event_id = :eventId AND event_type = 'AgentRunCompleted.v1'
                          AND status = 'QUEUED'
                        FOR UPDATE
                        """, Map.of("eventId", eventId), (rs, rowNum) -> mapEvent(rs))
                .stream().findFirst().orElse(null);
    }

    private CompletedRun lockRun(CompletionEvent event) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_code, agent_version_id, process_instance_id, execution_id,
                               activity_id, activity_activation_id, current_attempt_id, status,
                               error_code, output_snapshot_json, output_mapping_json,
                               process_failure_policy
                        FROM agent_run
                        WHERE id = :runId AND tenant_code = :tenantCode
                          AND trigger_type = 'FLOWABLE'
                          AND status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                        FOR UPDATE
                        """, Map.of("runId", event.runId(), "tenantCode", event.tenantCode()),
                (rs, rowNum) -> mapRun(rs)).stream().findFirst().orElse(null);
    }

    private void recoverFlowable(CompletedRun run, CompletionEvent event) {
        if (run.currentAttemptId() == null || run.currentAttemptId() != event.attemptId()
                || !safeEquals(run.activityActivationId(), event.activityActivationId())) {
            return; // Stale or late Attempt: acknowledge without advancing Flowable.
        }
        jdbcTemplate.queryForList("""
                SELECT ID_ FROM ACT_RU_EXECUTION
                WHERE ID_ = :executionId AND PROC_INST_ID_ = :processInstanceId
                FOR UPDATE
                """, Map.of("executionId", run.executionId(),
                "processInstanceId", run.processInstanceId()), String.class);
        Execution execution = runtimeService.createExecutionQuery()
                .executionId(run.executionId()).singleResult();
        if (execution == null || !safeEquals(execution.getActivityId(), run.activityId())) {
            return;
        }
        Object activeActivation = runtimeService.getVariableLocal(
                run.executionId(), AgentTaskExecutionListener.ACTIVATION_VARIABLE);
        if (!safeEquals(String.valueOf(activeActivation), run.activityActivationId())) {
            return;
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("agentRunId", run.id());
        variables.put("agentRunStatus", run.status());
        if ("SUCCEEDED".equals(run.status())) {
            variables.putAll(mapOutput(run.outputSnapshotJson(), run.outputMappingJson()));
            runtimeService.trigger(run.executionId(), variables);
            return;
        }
        variables.put("agentRunErrorCode", run.errorCode() == null ? "AGENT_FAILED" : run.errorCode());
        if ("CONTINUE_EMPTY".equals(run.processFailurePolicy())) {
            runtimeService.trigger(run.executionId(), variables);
        } else {
            runtimeService.setVariablesLocal(run.executionId(), variables);
        }
    }

    private Map<String, Object> mapOutput(String snapshotJson, String mappingJson) {
        try {
            JsonNode snapshot = objectMapper.readTree(snapshotJson == null ? "{}" : snapshotJson);
            JsonNode content = snapshot.path("content");
            if (content.isTextual()) {
                content = objectMapper.readTree(content.textValue());
            }
            JsonNode mappedContent = content;
            JsonNode mapping = objectMapper.readTree(mappingJson == null ? "{}" : mappingJson);
            Map<String, Object> variables = new HashMap<>();
            mapping.fields().forEachRemaining(entry -> {
                String variableName = entry.getValue().asText();
                if (!variableName.matches("[A-Za-z][A-Za-z0-9_]{0,127}")
                        || ProcessVariablePolicy.isInternalVariable(variableName)) {
                    throw new IllegalStateException("Unsafe Agent output variable: " + variableName);
                }
                JsonNode value = resolvePath(mappedContent, entry.getKey());
                if (value == null || value.isMissingNode()) {
                    throw new IllegalStateException("Agent output mapping path is missing: " + entry.getKey());
                }
                variables.put(variableName, objectMapper.convertValue(value, Object.class));
            });
            return variables;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to apply Agent output mapping", exception);
        }
    }

    private JsonNode resolvePath(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            current = current.path(segment);
            if (current.isMissingNode()) {
                return current;
            }
        }
        return current;
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
                INSERT INTO platform_inbox_event (
                    event_id, consumer_name, tenant_code, received_at
                ) VALUES (:eventId, :consumerName, :tenantCode, CURRENT_TIMESTAMP)
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

    private void markDelivered(UUID eventId) {
        jdbcTemplate.update("""
                UPDATE platform_outbox_event
                SET status = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE event_id = :eventId AND status = 'QUEUED'
                """, Map.of("eventId", eventId));
    }

    private void markRunHandled(CompletedRun run) {
        jdbcTemplate.update("""
                UPDATE agent_run SET workflow_resumed_at = :handledAt, updated_at = :handledAt
                WHERE id = :id AND tenant_code = :tenantCode AND workflow_resumed_at IS NULL
                """, Map.of("id", run.id(), "tenantCode", run.tenantCode(),
                "handledAt", OffsetDateTime.now(ZoneOffset.UTC)));
    }

    private CompletionEvent mapEvent(ResultSet rs) throws SQLException {
        try {
            JsonNode payload = objectMapper.readTree(rs.getString("payload"));
            return new CompletionEvent(
                    rs.getObject("event_id", UUID.class), rs.getString("tenant_code"),
                    payload.path("runId").asLong(), payload.path("attemptId").asLong(),
                    payload.path("activityActivationId").asText(), rs.getString("trace_id"));
        } catch (Exception exception) {
            throw new SQLException("Invalid Agent completion event payload", exception);
        }
    }

    private CompletedRun mapRun(ResultSet rs) throws SQLException {
        return new CompletedRun(
                rs.getLong("id"), rs.getString("tenant_code"), rs.getLong("agent_version_id"),
                rs.getString("process_instance_id"), rs.getString("execution_id"),
                rs.getString("activity_id"), rs.getString("activity_activation_id"),
                rs.getObject("current_attempt_id", Long.class), rs.getString("status"),
                rs.getString("error_code"), rs.getString("output_snapshot_json"),
                rs.getString("output_mapping_json"), rs.getString("process_failure_policy"));
    }

    private boolean safeEquals(String left, String right) {
        return left != null && left.equals(right);
    }

    private record CompletionEvent(
            UUID eventId, String tenantCode, long runId, long attemptId,
            String activityActivationId, String traceId
    ) {
    }

    private record CompletedRun(
            long id, String tenantCode, long agentVersionId, String processInstanceId,
            String executionId, String activityId, String activityActivationId,
            Long currentAttemptId, String status, String errorCode,
            String outputSnapshotJson, String outputMappingJson, String processFailurePolicy
    ) {
    }
}
