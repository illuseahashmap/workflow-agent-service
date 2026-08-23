package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

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
        recordOperation(tenantCode, runId, previousStatuses.getFirst(), operatorId,
                traceId, reason, retryWindowSeconds);
        return true;
    }

    @Override
    public void recordOperation(
            String tenantCode, long runId, String previousStatus, String operatorId,
            String traceId, String reason, int retryWindowSeconds) {
        jdbcTemplate.update("""
                INSERT INTO agent_run_operation (
                    tenant_code, agent_run_id, operation_type, previous_status,
                    resulting_status, operator_id, trace_id, reason, retry_window_seconds)
                VALUES (:tenantCode, :runId, 'RETRY', :previousStatus,
                        'QUEUED', :operatorId, :traceId, :reason, :retryWindowSeconds)
                """, Map.of("tenantCode", tenantCode, "runId", runId,
                "previousStatus", previousStatus, "operatorId", operatorId,
                "traceId", traceId, "reason", reason,
                "retryWindowSeconds", retryWindowSeconds));
    }
}
