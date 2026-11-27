package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.agent.runtime.application.port.AgentRunOperationsRepository.OperationResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence adapter for operator commands, separate from worker execution persistence. */
@Repository
public class JdbcAgentRunOperationsRepository
        implements io.github.illuseahashmap.agent.runtime.application.port.AgentRunOperationsRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcAgentRunOperationsRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean requeueFailed(
            String tenantCode, long runId, String operatorId, String traceId, String reason,
            int retryWindowSeconds) {
        Map<String, Object> parameters = Map.of(
                "tenantCode", tenantCode, "runId", runId, "operatorId", operatorId,
                "traceId", traceId, "reason", reason, "retryWindowSeconds", retryWindowSeconds);
        List<String> previousStatuses = jdbcTemplate.query("""
                WITH candidate AS (
                    SELECT id, status, deadline_at
                    FROM agent_run
                    WHERE id = :runId AND tenant_code = :tenantCode
                    FOR UPDATE
                ), changed AS (
                    UPDATE agent_run run
                    SET status = 'QUEUED', current_attempt_id = NULL,
                        lease_owner = NULL, lease_expires_at = NULL,
                        pause_requested_at = NULL,
                        available_at = CURRENT_TIMESTAMP,
                        deadline_at = GREATEST(candidate.deadline_at,
                            CURRENT_TIMESTAMP + (:retryWindowSeconds * INTERVAL '1 second')),
                        completed_at = NULL,
                        result_status = NULL, error_code = NULL, updated_at = CURRENT_TIMESTAMP
                    FROM candidate
                    WHERE run.id = candidate.id
                      AND candidate.status IN ('FAILED', 'TIMED_OUT', 'CANCELLED')
                    RETURNING run.id, candidate.status AS old_status
                )
                INSERT INTO agent_run_state_history (
                    tenant_code, agent_run_id, attempt_id, old_status, new_status,
                    reason_code, operator_type, operator_id, trace_id, created_at
                )
                SELECT :tenantCode, id, NULL, old_status, 'QUEUED',
                       'OPERATOR_REQUEUED', 'USER', :operatorId, :traceId, CURRENT_TIMESTAMP
                FROM changed
                RETURNING old_status
                """, parameters, (resultSet, rowNum) -> resultSet.getString("old_status"));
        if (previousStatuses.isEmpty()) {
            return false;
        }
        recordOperation(tenantCode, runId, "RETRY", previousStatuses.getFirst(), "QUEUED",
                operatorId, traceId, reason, retryWindowSeconds);
        return true;
    }

    @Override
    public boolean cancelActive(
            String tenantCode, long runId, String operatorId, String traceId, String reason) {
        Map<String, Object> parameters = Map.of(
                "tenantCode", tenantCode, "runId", runId, "operatorId", operatorId,
                "traceId", traceId, "reason", reason);
        List<RunState> candidates = jdbcTemplate.query("""
                SELECT status, current_attempt_id
                FROM agent_run
                WHERE id = :runId AND tenant_code = :tenantCode
                  AND status IN ('QUEUED', 'RUNNING', 'PAUSED')
                FOR UPDATE
                """, parameters, (resultSet, rowNum) -> new RunState(
                resultSet.getString("status"), resultSet.getObject("current_attempt_id", Long.class)));
        if (candidates.isEmpty()) {
            return false;
        }
        RunState candidate = candidates.getFirst();
        if (candidate.attemptId() != null) {
            jdbcTemplate.update("""
                    UPDATE agent_run_attempt
                    SET status = 'CANCELLED', error_code = 'OPERATOR_CANCELLED',
                        completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                    WHERE tenant_code = :tenantCode AND agent_run_id = :runId
                      AND id = :attemptId AND status = 'RUNNING'
                    """, Map.of("tenantCode", tenantCode, "runId", runId,
                    "attemptId", candidate.attemptId()));
            jdbcTemplate.update("""
                    UPDATE agent_run_step
                    SET status = 'FAILED', error_code = 'OPERATOR_CANCELLED',
                        completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                    WHERE tenant_code = :tenantCode AND agent_run_id = :runId
                      AND attempt_id = :attemptId AND status IN ('PENDING', 'RUNNING')
                    """, Map.of("tenantCode", tenantCode, "runId", runId,
                    "attemptId", candidate.attemptId()));
        }
        int updated = jdbcTemplate.update("""
                UPDATE agent_run
                SET status = 'CANCELLED', current_attempt_id = NULL,
                    lease_owner = NULL, lease_expires_at = NULL,
                    pause_requested_at = NULL,
                    completed_at = CURRENT_TIMESTAMP, result_status = NULL,
                    error_code = 'OPERATOR_CANCELLED', updated_at = CURRENT_TIMESTAMP
                WHERE id = :runId AND tenant_code = :tenantCode
                  AND status = :previousStatus
                """, Map.of("tenantCode", tenantCode, "runId", runId,
                "previousStatus", candidate.status()));
        if (updated != 1) {
            return false;
        }
        Map<String, Object> historyParameters = new HashMap<>();
        historyParameters.put("tenantCode", tenantCode);
        historyParameters.put("runId", runId);
        historyParameters.put("attemptId", candidate.attemptId());
        historyParameters.put("previousStatus", candidate.status());
        historyParameters.put("operatorId", operatorId);
        historyParameters.put("traceId", traceId);
        jdbcTemplate.update("""
                INSERT INTO agent_run_state_history (
                    tenant_code, agent_run_id, attempt_id, old_status, new_status,
                    reason_code, operator_type, operator_id, trace_id, created_at
                ) VALUES (
                    :tenantCode, :runId, :attemptId, :previousStatus, 'CANCELLED',
                    'OPERATOR_CANCELLED', 'USER', :operatorId, :traceId, CURRENT_TIMESTAMP
                )
                """, historyParameters);
        recordOperation(tenantCode, runId, "CANCEL", candidate.status(), "CANCELLED",
                operatorId, traceId, reason);
        return true;
    }

    @Override
    public OperationResult requestPause(
            String tenantCode, long runId, String operatorId, String traceId, String reason) {
        Map<String, Object> parameters = Map.of(
                "tenantCode", tenantCode, "runId", runId, "operatorId", operatorId,
                "traceId", traceId, "reason", reason);
        List<PauseState> candidates = jdbcTemplate.query("""
                SELECT status, current_attempt_id, pause_requested_at IS NOT NULL AS pause_requested
                FROM agent_run
                WHERE id = :runId AND tenant_code = :tenantCode
                FOR UPDATE
                """, parameters, (resultSet, rowNum) -> new PauseState(
                resultSet.getString("status"),
                resultSet.getObject("current_attempt_id", Long.class),
                resultSet.getBoolean("pause_requested")));
        if (candidates.isEmpty()) {
            return OperationResult.REJECTED;
        }
        PauseState candidate = candidates.getFirst();
        if ("PAUSED".equals(candidate.status()) || candidate.pauseRequested()) {
            return OperationResult.ALREADY_APPLIED;
        }
        if ("QUEUED".equals(candidate.status())) {
            int updated = jdbcTemplate.update("""
                    UPDATE agent_run
                    SET status = 'PAUSED', paused_at = CURRENT_TIMESTAMP,
                        pause_reason = :reason, updated_at = CURRENT_TIMESTAMP
                    WHERE id = :runId AND tenant_code = :tenantCode AND status = 'QUEUED'
                    """, parameters);
            if (updated != 1) {
                return OperationResult.REJECTED;
            }
            insertStateHistory(parameters, null, "QUEUED", "PAUSED", "OPERATOR_PAUSED");
            recordOperation(tenantCode, runId, "PAUSE", "QUEUED", "PAUSED",
                    operatorId, traceId, reason);
            return OperationResult.APPLIED;
        }
        if (!"RUNNING".equals(candidate.status())) {
            return OperationResult.REJECTED;
        }
        int updated = jdbcTemplate.update("""
                UPDATE agent_run
                SET pause_requested_at = CURRENT_TIMESTAMP, pause_reason = :reason,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :runId AND tenant_code = :tenantCode
                  AND status = 'RUNNING' AND pause_requested_at IS NULL
                """, parameters);
        if (updated != 1) {
            return OperationResult.ALREADY_APPLIED;
        }
        insertStateHistory(parameters, candidate.attemptId(), "RUNNING", "RUNNING",
                "OPERATOR_PAUSE_REQUESTED");
        recordOperation(tenantCode, runId, "PAUSE_REQUEST", "RUNNING", "RUNNING",
                operatorId, traceId, reason);
        return OperationResult.APPLIED;
    }

    @Override
    public OperationResult resumePaused(
            String tenantCode, long runId, String operatorId, String traceId, String reason,
            int resumeWindowSeconds) {
        Map<String, Object> parameters = Map.of(
                "tenantCode", tenantCode, "runId", runId, "operatorId", operatorId,
                "traceId", traceId, "reason", reason, "resumeWindowSeconds", resumeWindowSeconds);
        List<ResumeState> candidates = jdbcTemplate.query("""
                SELECT status, paused_at IS NOT NULL AS paused_before
                FROM agent_run
                WHERE id = :runId AND tenant_code = :tenantCode
                FOR UPDATE
                """, parameters, (resultSet, rowNum) -> new ResumeState(
                resultSet.getString("status"), resultSet.getBoolean("paused_before")));
        if (candidates.isEmpty()) {
            return OperationResult.REJECTED;
        }
        ResumeState candidate = candidates.getFirst();
        if ("QUEUED".equals(candidate.status()) && candidate.pausedBefore()) {
            return OperationResult.ALREADY_APPLIED;
        }
        if (!"PAUSED".equals(candidate.status())) {
            return OperationResult.REJECTED;
        }
        int updated = jdbcTemplate.update("""
                UPDATE agent_run
                SET status = 'QUEUED', current_attempt_id = NULL,
                    lease_owner = NULL, lease_expires_at = NULL,
                    pause_requested_at = NULL, available_at = CURRENT_TIMESTAMP,
                    deadline_at = GREATEST(deadline_at,
                        CURRENT_TIMESTAMP + (:resumeWindowSeconds * INTERVAL '1 second')),
                    completed_at = NULL, result_status = NULL, error_code = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :runId AND tenant_code = :tenantCode AND status = 'PAUSED'
                """, parameters);
        if (updated != 1) {
            return OperationResult.REJECTED;
        }
        insertStateHistory(parameters, null, "PAUSED", "QUEUED", "OPERATOR_RESUMED");
        recordOperation(tenantCode, runId, "RESUME", "PAUSED", "QUEUED",
                operatorId, traceId, reason, resumeWindowSeconds);
        return OperationResult.APPLIED;
    }

    @Override
    public void recordOperation(
            String tenantCode, long runId, String operationType, String previousStatus,
            String resultingStatus, String operatorId, String traceId, String reason,
            Integer retryWindowSeconds) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantCode", tenantCode);
        parameters.put("runId", runId);
        parameters.put("operationType", operationType);
        parameters.put("previousStatus", previousStatus);
        parameters.put("resultingStatus", resultingStatus);
        parameters.put("operatorId", operatorId);
        parameters.put("traceId", traceId);
        parameters.put("reason", reason);
        parameters.put("retryWindowSeconds", retryWindowSeconds);
        jdbcTemplate.update("""
                INSERT INTO agent_run_operation (
                    tenant_code, agent_run_id, operation_type, previous_status,
                    resulting_status, operator_id, trace_id, reason, retry_window_seconds)
                VALUES (:tenantCode, :runId, :operationType, :previousStatus,
                        :resultingStatus, :operatorId, :traceId, :reason, :retryWindowSeconds)
                """, parameters);
    }

    private void insertStateHistory(
            Map<String, Object> parameters,
            Long attemptId,
            String previousStatus,
            String resultingStatus,
            String reasonCode
    ) {
        Map<String, Object> history = new HashMap<>(parameters);
        history.put("attemptId", attemptId);
        history.put("previousStatus", previousStatus);
        history.put("resultingStatus", resultingStatus);
        history.put("reasonCode", reasonCode);
        jdbcTemplate.update("""
                INSERT INTO agent_run_state_history (
                    tenant_code, agent_run_id, attempt_id, old_status, new_status,
                    reason_code, operator_type, operator_id, trace_id, created_at
                ) VALUES (
                    :tenantCode, :runId, :attemptId, :previousStatus, :resultingStatus,
                    :reasonCode, 'USER', :operatorId, :traceId, CURRENT_TIMESTAMP
                )
                """, history);
    }

    private record RunState(String status, Long attemptId) {
    }

    private record PauseState(String status, Long attemptId, boolean pauseRequested) {
    }

    private record ResumeState(String status, boolean pausedBefore) {
    }
}
