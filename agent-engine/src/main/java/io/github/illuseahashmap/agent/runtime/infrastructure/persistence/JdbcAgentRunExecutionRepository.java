package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunExecutionRepository;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunExecutionSnapshot;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRecoveryDecision;
import io.github.illuseahashmap.agent.runtime.domain.AgentRun;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStateTransition;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStatus;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentRunExecutionRepository implements AgentRunExecutionRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcAgentRunExecutionRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AgentRun insertQueued(Submission submission) {
        long id = jdbcTemplate.queryForObject("""
                        INSERT INTO agent_run (
                            tenant_code, idempotency_key, agent_version_id, status,
                            trigger_type, input_snapshot_json, requested_by,
                            process_instance_id, execution_id, activity_id, activity_activation_id,
                            output_mapping_json, process_failure_policy, process_wait_timeout_seconds,
                            deadline_at, available_at, created_at, updated_at
                        ) VALUES (
                            :tenantCode, :idempotencyKey, :agentVersionId, 'QUEUED',
                            :triggerType, CAST(:inputSnapshotJson AS jsonb), :requestedBy,
                            :processInstanceId, :executionId, :activityId, :activityActivationId,
                            CAST(:outputMappingJson AS jsonb), :processFailurePolicy, :processWaitTimeoutSeconds,
                            :deadlineAt, :createdAt, :createdAt, :createdAt
                        ) RETURNING id
                        """,
                nullableMap(
                        "tenantCode", submission.tenantCode(),
                        "idempotencyKey", submission.idempotencyKey(),
                        "agentVersionId", submission.agentVersionId(),
                        "triggerType", submission.triggerType().name(),
                        "inputSnapshotJson", submission.inputSnapshotJson(),
                        "requestedBy", submission.requestedBy(),
                        "processInstanceId", submission.processInstanceId(),
                        "executionId", submission.executionId(),
                        "activityId", submission.activityId(),
                        "activityActivationId", submission.activityActivationId(),
                        "outputMappingJson", submission.outputMappingJson(),
                        "processFailurePolicy", submission.processFailurePolicy(),
                        "processWaitTimeoutSeconds", submission.processWaitTimeoutSeconds(),
                        "deadlineAt", timestamp(submission.deadlineAt()),
                        "createdAt", timestamp(submission.createdAt())),
                Long.class);
        return AgentRun.queued(
                id,
                submission.tenantCode(),
                submission.agentVersionId(),
                submission.idempotencyKey(),
                submission.deadlineAt(),
                submission.createdAt(),
                submission.processInstanceId(),
                submission.executionId(),
                submission.activityId(),
                submission.activityActivationId());
    }

    @Override
    public Optional<AgentRunExecutionSnapshot> lockNextAvailable(Instant now) {
        return lockNextAvailable(now, Integer.MAX_VALUE);
    }

    @Override
    public Optional<AgentRunExecutionSnapshot> lockNextAvailable(Instant now, int tenantConcurrencyLimit) {
        return jdbcTemplate.query("""
                        SELECT *
                        FROM agent_run
                        WHERE status = 'QUEUED'
                          AND available_at <= :now
                          AND deadline_at > :now
                          AND (
                              :tenantConcurrencyLimit >= 2147483647
                              OR (SELECT COUNT(*) FROM agent_run active
                                  WHERE active.tenant_code = agent_run.tenant_code
                                    AND active.status = 'RUNNING') < :tenantConcurrencyLimit
                          )
                        ORDER BY available_at, id
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                        """,
                Map.of("now", timestamp(now),
                        "tenantConcurrencyLimit", Math.max(1, tenantConcurrencyLimit)),
                (resultSet, rowNum) -> new AgentRunExecutionSnapshot(
                        mapRun(resultSet), resultSet.getString("input_snapshot_json")))
                .stream()
                .findFirst();
    }

    @Override
    public int recoverExpired(Instant now) {
        List<RecoveryCandidate> candidates = jdbcTemplate.query("""
                        SELECT id, tenant_code, status, current_attempt_id, deadline_at
                        FROM agent_run
                        WHERE (status = 'QUEUED' AND deadline_at <= :now)
                           OR (status = 'RUNNING' AND lease_expires_at <= :now)
                        ORDER BY id
                        FOR UPDATE SKIP LOCKED
                        LIMIT 100
                        """,
                Map.of("now", timestamp(now)),
                (rs, rowNum) -> new RecoveryCandidate(
                        rs.getLong("id"),
                        rs.getString("tenant_code"),
                        AgentRunStatus.valueOf(rs.getString("status")),
                        rs.getObject("current_attempt_id", Long.class),
                        instant(rs, "deadline_at")));
        int recovered = 0;
        for (RecoveryCandidate candidate : candidates) {
            boolean timedOut = !now.isBefore(candidate.deadlineAt());
            if (candidate.status() == AgentRunStatus.RUNNING && candidate.currentAttemptId() != null) {
                jdbcTemplate.update("""
                                UPDATE agent_run_attempt
                                SET status = 'FAILED', error_code = 'LEASE_EXPIRED',
                                    completed_at = :now, updated_at = :now
                                WHERE tenant_code = :tenantCode AND agent_run_id = :runId
                                  AND id = :attemptId AND status = 'RUNNING'
                                """,
                        Map.of("tenantCode", candidate.tenantCode(), "runId", candidate.runId(),
                                "attemptId", candidate.currentAttemptId(), "now", timestamp(now)));
                jdbcTemplate.update("""
                                UPDATE agent_run_step
                                SET status = 'FAILED', error_code = 'LEASE_EXPIRED',
                                    completed_at = :now, updated_at = :now
                                WHERE tenant_code = :tenantCode AND agent_run_id = :runId
                                  AND attempt_id = :attemptId AND status = 'RUNNING'
                                """,
                        Map.of("tenantCode", candidate.tenantCode(), "runId", candidate.runId(),
                                "attemptId", candidate.currentAttemptId(), "now", timestamp(now)));
            }
            AgentRunStatus target = timedOut ? AgentRunStatus.TIMED_OUT : AgentRunStatus.QUEUED;
            int updated = jdbcTemplate.update("""
                            UPDATE agent_run
                            SET status = :status, current_attempt_id = NULL,
                                lease_owner = NULL, lease_expires_at = NULL,
                                available_at = :availableAt,
                                completed_at = :completedAt, error_code = :errorCode,
                                result_status = :resultStatus, updated_at = :now
                            WHERE tenant_code = :tenantCode AND id = :runId
                              AND status = :oldStatus
                            """,
                    nullableMap(
                            "status", target.name(), "availableAt", timestamp(now),
                            "completedAt", timedOut ? timestamp(now) : null,
                            "errorCode", timedOut ? "AGENT_DEADLINE_EXCEEDED" : "LEASE_EXPIRED",
                            "resultStatus", timedOut ? "FAILED" : null,
                            "now", timestamp(now), "tenantCode", candidate.tenantCode(),
                            "runId", candidate.runId(), "oldStatus", candidate.status().name()));
            if (updated == 1) {
                insertTransition(new AgentRunStateTransition(
                        candidate.tenantCode(), candidate.runId(), candidate.currentAttemptId(),
                        candidate.status(), target, timedOut ? "AGENT_DEADLINE_EXCEEDED" : "LEASE_EXPIRED",
                        io.github.illuseahashmap.agent.runtime.domain.AgentRunOperatorType.SYSTEM,
                        "agent-recovery", UUID.randomUUID().toString(), now));
                recovered++;
            }
        }
        return recovered;
    }

    @Override
    public boolean renewLease(
            String tenantCode,
            long runId,
            long attemptId,
            String leaseOwner,
            Instant renewedAt,
            Instant leaseExpiresAt
    ) {
        int updated = jdbcTemplate.update("""
                        UPDATE agent_run
                        SET lease_expires_at = :leaseExpiresAt, updated_at = :renewedAt
                        WHERE tenant_code = :tenantCode AND id = :runId
                          AND status = 'RUNNING' AND current_attempt_id = :attemptId
                          AND lease_owner = :leaseOwner AND lease_expires_at > :renewedAt
                          AND deadline_at >= :leaseExpiresAt
                        """,
                Map.of(
                        "tenantCode", tenantCode,
                        "runId", runId,
                        "attemptId", attemptId,
                        "leaseOwner", leaseOwner,
                        "renewedAt", timestamp(renewedAt),
                        "leaseExpiresAt", timestamp(leaseExpiresAt)));
        return updated == 1;
    }

    @Override
    public int nextAttemptNumber(String tenantCode, long runId) {
        return jdbcTemplate.queryForObject("""
                        SELECT COALESCE(MAX(attempt_no), 0) + 1
                        FROM agent_run_attempt
                        WHERE tenant_code = :tenantCode AND agent_run_id = :runId
                        """,
                Map.of("tenantCode", tenantCode, "runId", runId), Integer.class);
    }

    @Override
    public long insertRunningAttempt(String tenantCode, long runId, int attemptNumber, Instant startedAt) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO agent_run_attempt (
                            tenant_code, agent_run_id, attempt_no, status, started_at
                        ) VALUES (
                            :tenantCode, :runId, :attemptNumber, 'RUNNING', :startedAt
                        ) RETURNING id
                        """,
                Map.of(
                        "tenantCode", tenantCode,
                        "runId", runId,
                        "attemptNumber", attemptNumber,
                        "startedAt", timestamp(startedAt)),
                Long.class);
    }

    @Override
    public long insertRunningStep(String tenantCode, long runId, long attemptId, Instant startedAt) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO agent_run_step (
                            tenant_code, agent_run_id, attempt_id, sequence_no,
                            step_type, status, started_at
                        ) VALUES (
                            :tenantCode, :runId, :attemptId, 1,
                            'MODEL_CALL', 'RUNNING', :startedAt
                        ) RETURNING id
                        """,
                Map.of(
                        "tenantCode", tenantCode,
                        "runId", runId,
                        "attemptId", attemptId,
                        "startedAt", timestamp(startedAt)),
                Long.class);
    }

    @Override
    public Optional<AgentRunExecutionRepository.CheckpointSnapshot> findLatestCheckpoint(
            String tenantCode, long runId) {
        return jdbcTemplate.query("""
                        SELECT c.sequence_no, c.checkpoint_type, c.snapshot_json
                        FROM agent_run_checkpoint c
                        JOIN agent_run_attempt a
                          ON a.id = c.attempt_id
                         AND a.tenant_code = c.tenant_code
                         AND a.agent_run_id = c.agent_run_id
                        WHERE c.tenant_code = :tenantCode
                          AND c.agent_run_id = :runId
                          AND c.checkpoint_type = 'STEP_COMPLETED'
                        ORDER BY a.attempt_no DESC, c.sequence_no DESC, c.created_at DESC, c.id DESC
                        LIMIT 1
                        """,
                Map.of("tenantCode", tenantCode, "runId", runId),
                (resultSet, rowNum) -> new AgentRunExecutionRepository.CheckpointSnapshot(
                        resultSet.getInt("sequence_no"),
                        resultSet.getString("checkpoint_type"),
                        resultSet.getString("snapshot_json")))
                .stream().findFirst();
    }

    @Override
    public boolean isCurrentLeaseValid(
            String tenantCode, long runId, long attemptId, String leaseOwner, Instant now) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM agent_run
                        WHERE tenant_code = :tenantCode
                          AND id = :runId
                          AND status = 'RUNNING'
                          AND current_attempt_id = :attemptId
                          AND lease_owner = :leaseOwner
                          AND lease_expires_at > :now
                        """, Map.of(
                        "tenantCode", tenantCode, "runId", runId, "attemptId", attemptId,
                        "leaseOwner", leaseOwner, "now", timestamp(now)), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public void insertCompletedStep(
            String tenantCode,
            long runId,
            long attemptId,
            int sequenceNo,
            String stepType,
            String status,
            String errorCode,
            Instant completedAt
    ) {
        jdbcTemplate.update("""
                        INSERT INTO agent_run_step (
                            tenant_code, agent_run_id, attempt_id, sequence_no,
                            step_type, status, error_code, started_at, completed_at, updated_at
                        ) VALUES (
                            :tenantCode, :runId, :attemptId, :sequenceNo,
                            :stepType, :status, :errorCode, :completedAt, :completedAt, :completedAt
                        )
                        """,
                Map.of(
                        "tenantCode", tenantCode,
                        "runId", runId,
                        "attemptId", attemptId,
                        "sequenceNo", sequenceNo,
                        "stepType", stepType,
                        "status", status,
                        "errorCode", errorCode == null ? "" : errorCode,
                        "completedAt", timestamp(completedAt)));
    }

    @Override
    public void insertCheckpoint(
            String tenantCode,
            long runId,
            long attemptId,
            int sequenceNo,
            String checkpointType,
            String snapshotJson,
            Instant createdAt
    ) {
        jdbcTemplate.update("""
                        INSERT INTO agent_run_checkpoint (
                            tenant_code, agent_run_id, attempt_id, sequence_no,
                            checkpoint_type, snapshot_json, created_at
                        ) VALUES (
                            :tenantCode, :runId, :attemptId, :sequenceNo,
                            :checkpointType, :snapshotJson, :createdAt
                        )
                        ON CONFLICT (tenant_code, agent_run_id, attempt_id, sequence_no)
                        DO NOTHING
                        """,
                Map.of(
                        "tenantCode", tenantCode,
                        "runId", runId,
                        "attemptId", attemptId,
                        "sequenceNo", sequenceNo,
                        "checkpointType", checkpointType,
                        "snapshotJson", snapshotJson,
                        "createdAt", timestamp(createdAt)));
    }

    @Override
    public void saveClaimed(AgentRun run, AgentRunStateTransition transition) {
        int updated = jdbcTemplate.update("""
                        UPDATE agent_run
                        SET status = :status,
                            current_attempt_id = :attemptId,
                            lease_owner = :leaseOwner,
                            lease_expires_at = :leaseExpiresAt,
                            started_at = COALESCE(started_at, :startedAt),
                            updated_at = :updatedAt
                        WHERE tenant_code = :tenantCode AND id = :runId AND status = 'QUEUED'
                        """,
                Map.of(
                        "status", run.status().name(),
                        "attemptId", run.currentAttemptId(),
                        "leaseOwner", run.leaseOwner(),
                        "leaseExpiresAt", timestamp(run.leaseExpiresAt()),
                        "startedAt", timestamp(run.startedAt()),
                        "updatedAt", timestamp(run.updatedAt()),
                        "tenantCode", run.tenantCode(),
                        "runId", run.id()));
        requireUpdated(updated, "Agent run claim was lost");
        insertTransition(transition);
    }

    @Override
    public void saveSucceeded(
            AgentRun run,
            long attemptId,
            long stepId,
            long providerId,
            String requestedModel,
            ModelProviderResponse response,
            String outputSnapshotJson,
            AgentRunStateTransition transition
    ) {
        Map<String, Object> identity = identity(run.tenantCode(), run.id(), attemptId, stepId);
        jdbcTemplate.update("""
                        UPDATE agent_run_attempt
                        SET status = 'SUCCEEDED', completed_at = :completedAt, updated_at = :completedAt
                        WHERE tenant_code = :tenantCode AND agent_run_id = :runId AND id = :attemptId
                        """,
                with(identity, "completedAt", timestamp(run.completedAt())));
        jdbcTemplate.update("""
                        UPDATE agent_run_step
                        SET status = 'SUCCEEDED', completed_at = :completedAt, updated_at = :completedAt
                        WHERE tenant_code = :tenantCode AND agent_run_id = :runId
                          AND attempt_id = :attemptId AND id = :stepId
                        """,
                with(identity, "completedAt", timestamp(run.completedAt())));
        insertInvocation(identity, providerId, requestedModel, response, null, run.completedAt());
        int updated = jdbcTemplate.update("""
                        UPDATE agent_run
                        SET status = 'SUCCEEDED', current_attempt_id = :attemptId,
                            lease_owner = NULL, lease_expires_at = NULL,
                            result_status = 'SUCCESS', error_code = NULL,
                            output_snapshot_json = CAST(:outputSnapshotJson AS jsonb),
                            completed_at = :completedAt, updated_at = :completedAt
                        WHERE tenant_code = :tenantCode AND id = :runId
                          AND status = 'RUNNING' AND current_attempt_id = :attemptId
                        """,
                with(identity,
                        "outputSnapshotJson", outputSnapshotJson,
                        "completedAt", timestamp(run.completedAt())));
        requireUpdated(updated, "Stale Agent attempt cannot complete the run");
        insertTransition(transition);
    }

    @Override
    public void saveFailed(
            AgentRun run,
            long attemptId,
            long stepId,
            long providerId,
            String requestedModel,
            String errorCode,
            ResultStatus resultStatus,
            Instant availableAt,
            AgentRunStateTransition transition
    ) {
        Map<String, Object> identity = identity(run.tenantCode(), run.id(), attemptId, stepId);
        jdbcTemplate.update("""
                        UPDATE agent_run_attempt
                        SET status = 'FAILED', error_code = :errorCode,
                            completed_at = :completedAt, updated_at = :completedAt
                        WHERE tenant_code = :tenantCode AND agent_run_id = :runId AND id = :attemptId
                        """,
                with(identity,
                        "errorCode", errorCode,
                        "completedAt", timestamp(run.updatedAt())));
        jdbcTemplate.update("""
                        UPDATE agent_run_step
                        SET status = 'FAILED', error_code = :errorCode,
                            completed_at = :completedAt, updated_at = :completedAt
                        WHERE tenant_code = :tenantCode AND agent_run_id = :runId
                          AND attempt_id = :attemptId AND id = :stepId
                        """,
                with(identity,
                        "errorCode", errorCode,
                        "completedAt", timestamp(run.updatedAt())));
        insertInvocation(identity, providerId, requestedModel, null, errorCode, run.updatedAt());

        boolean terminal = run.status().isTerminal();
        var parameters = with(identity,
                "status", run.status().name(),
                "terminal", terminal,
                "errorCode", errorCode,
                "availableAt", timestamp(availableAt),
                "completedAt", run.completedAt() == null ? null : timestamp(run.completedAt()),
                "resultStatus", terminal ? resultStatus.name() : null,
                "updatedAt", timestamp(run.updatedAt()));
        int updated = jdbcTemplate.update("""
                        UPDATE agent_run
                        SET status = :status,
                            current_attempt_id = CASE WHEN :terminal THEN :attemptId ELSE NULL END,
                            lease_owner = NULL,
                            lease_expires_at = NULL,
                            available_at = :availableAt,
                            result_status = :resultStatus,
                            error_code = :errorCode,
                            completed_at = :completedAt,
                            updated_at = :updatedAt
                        WHERE tenant_code = :tenantCode AND id = :runId
                          AND status = 'RUNNING' AND current_attempt_id = :attemptId
                        """,
                parameters);
        requireUpdated(updated, "Stale Agent attempt cannot fail the run");
        insertTransition(transition);
    }

    @Override
    public void insertRecoveryDecision(AgentRecoveryDecision decision) {
        jdbcTemplate.update("""
                        INSERT INTO agent_recovery_decision (
                            tenant_code, agent_run_id, attempt_id, step_id, error_code,
                            failure_category, action, retry_scheduled, requires_human_review,
                            result_status, reason, created_at
                        ) VALUES (
                            :tenantCode, :runId, :attemptId, :stepId, :errorCode,
                            :failureCategory, :action, :retryScheduled, :requiresHumanReview,
                            :resultStatus, :reason, :createdAt
                        )
                        """,
                nullableMap(
                        "tenantCode", decision.tenantCode(),
                        "runId", decision.agentRunId(),
                        "attemptId", decision.attemptId(),
                        "stepId", decision.stepId(),
                        "errorCode", decision.errorCode(),
                        "failureCategory", decision.failureCategory().name(),
                        "action", decision.action().name(),
                        "retryScheduled", decision.retryScheduled(),
                        "requiresHumanReview", decision.requiresHumanReview(),
                        "resultStatus", decision.resultStatus() == null ? null : decision.resultStatus().name(),
                        "reason", decision.reason(),
                        "createdAt", timestamp(decision.createdAt())));
    }

    private void insertInvocation(
            Map<String, Object> identity,
            long providerId,
            String requestedModel,
            ModelProviderResponse response,
            String errorCode,
            Instant completedAt
    ) {
        var parameters = with(identity,
                "providerId", providerId,
                "requestedModel", requestedModel,
                "actualModel", response == null ? null : response.actualModel(),
                "providerRequestId", response == null ? null : response.providerRequestId(),
                "finishReason", response == null ? null : response.finishReason(),
                "status", response == null ? "FAILED" : "SUCCEEDED",
                "inputTokens", response == null ? 0 : response.inputTokens(),
                "outputTokens", response == null ? 0 : response.outputTokens(),
                "reasoningTokens", response == null ? 0 : response.reasoningTokens(),
                "latencyMs", response == null ? null : response.latencyMillis(),
                "errorCode", errorCode,
                "completedAt", timestamp(completedAt));
        jdbcTemplate.update("""
                        INSERT INTO agent_model_invocation (
                            tenant_code, agent_run_id, attempt_id, step_id, provider_id,
                            requested_model, actual_model, provider_request_id, finish_reason,
                            status, input_tokens, output_tokens, reasoning_tokens,
                            latency_ms, error_code, completed_at
                        ) VALUES (
                            :tenantCode, :runId, :attemptId, :stepId, :providerId,
                            :requestedModel, :actualModel, :providerRequestId, :finishReason,
                            :status, :inputTokens, :outputTokens, :reasoningTokens,
                            :latencyMs, :errorCode, :completedAt
                        )
                        """,
                parameters);
    }

    private void insertTransition(AgentRunStateTransition transition) {
        jdbcTemplate.update("""
                        INSERT INTO agent_run_state_history (
                            tenant_code, agent_run_id, attempt_id, old_status, new_status,
                            reason_code, operator_type, operator_id, trace_id, created_at
                        ) VALUES (
                            :tenantCode, :runId, :attemptId, :oldStatus, :newStatus,
                            :reasonCode, :operatorType, :operatorId, :traceId, :createdAt
                        )
                        """,
                nullableMap(
                        "tenantCode", transition.tenantCode(),
                        "runId", transition.agentRunId(),
                        "attemptId", transition.attemptId(),
                        "oldStatus", transition.oldStatus().name(),
                        "newStatus", transition.newStatus().name(),
                        "reasonCode", transition.reasonCode(),
                        "operatorType", transition.operatorType().name(),
                        "operatorId", transition.operatorId(),
                        "traceId", transition.traceId(),
                        "createdAt", timestamp(transition.createdAt())));
    }

    private AgentRun mapRun(ResultSet resultSet) throws SQLException {
        return AgentRun.restore(
                resultSet.getLong("id"),
                resultSet.getString("tenant_code"),
                resultSet.getLong("agent_version_id"),
                resultSet.getString("idempotency_key"),
                AgentRunStatus.valueOf(resultSet.getString("status")),
                resultSet.getObject("current_attempt_id", Long.class),
                resultSet.getString("lease_owner"),
                instant(resultSet, "lease_expires_at"),
                instant(resultSet, "deadline_at"),
                instant(resultSet, "started_at"),
                instant(resultSet, "completed_at"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                resultSet.getString("process_instance_id"),
                resultSet.getString("execution_id"),
                resultSet.getString("activity_id"),
                resultSet.getString("activity_activation_id"));
    }

    private Map<String, Object> identity(String tenantCode, long runId, long attemptId, long stepId) {
        return nullableMap(
                "tenantCode", tenantCode,
                "runId", runId,
                "attemptId", attemptId,
                "stepId", stepId);
    }

    private Map<String, Object> with(Map<String, Object> source, Object... entries) {
        var values = new HashMap<>(source);
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return values;
    }

    private Map<String, Object> nullableMap(Object... entries) {
        var values = new HashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return values;
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private void requireUpdated(int updated, String message) {
        if (updated != 1) {
            throw new IllegalStateException(message);
        }
    }

    private record RecoveryCandidate(
            long runId,
            String tenantCode,
            AgentRunStatus status,
            Long currentAttemptId,
            Instant deadlineAt
    ) {
    }
}
