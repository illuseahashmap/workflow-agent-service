package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.runtime.domain.AgentRun;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStateTransition;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunTriggerType;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import java.time.Instant;
import java.util.Optional;

public interface AgentRunExecutionRepository {

    AgentRun insertQueued(Submission submission);

    Optional<AgentRunExecutionSnapshot> lockNextAvailable(Instant now);

    /**
     * Claims one run while respecting a persistent per-tenant concurrency cap.
     * The default keeps lightweight adapters source-compatible.
     */
    default Optional<AgentRunExecutionSnapshot> lockNextAvailable(Instant now, int tenantConcurrencyLimit) {
        return lockNextAvailable(now);
    }

    int recoverExpired(Instant now);

    boolean renewLease(
            String tenantCode,
            long runId,
            long attemptId,
            String leaseOwner,
            Instant renewedAt,
            Instant leaseExpiresAt
    );

    int nextAttemptNumber(String tenantCode, long runId);

    long insertRunningAttempt(String tenantCode, long runId, int attemptNumber, Instant startedAt);

    long insertRunningStep(String tenantCode, long runId, long attemptId, Instant startedAt);

    /** Returns the last complete checkpoint across prior attempts for this run. */
    default Optional<CheckpointSnapshot> findLatestCheckpoint(String tenantCode, long runId) {
        return Optional.empty();
    }

    default boolean isCurrentLeaseValid(
            String tenantCode, long runId, long attemptId, String leaseOwner, Instant now) {
        return true;
    }

    /** Persists a completed child step after an executor has returned its bounded result. */
    default void insertCompletedStep(
            String tenantCode,
            long runId,
            long attemptId,
            int sequenceNo,
            String stepType,
            String status,
            String errorCode,
            Instant completedAt
    ) {
        // Compatibility default for in-memory and legacy adapters.
    }

    /** Persists a durable boundary after a child step has completed. */
    default void insertCheckpoint(
            String tenantCode,
            long runId,
            long attemptId,
            int sequenceNo,
            String checkpointType,
            String snapshotJson,
            Instant createdAt
    ) {
        // Compatibility default for in-memory and legacy adapters.
    }

    default boolean insertProgressIfCurrentLeaseValid(
            String tenantCode,
            long runId,
            long attemptId,
            String leaseOwner,
            int sequenceNo,
            String stepType,
            String status,
            String errorCode,
            String checkpointType,
            String snapshotJson,
            Instant now
    ) {
        if (!isCurrentLeaseValid(tenantCode, runId, attemptId, leaseOwner, now)) {
            return false;
        }
        insertCompletedStep(tenantCode, runId, attemptId, sequenceNo,
                stepType, status, errorCode, now);
        insertCheckpoint(tenantCode, runId, attemptId, sequenceNo,
                checkpointType, snapshotJson, now);
        return true;
    }

    record CheckpointSnapshot(int sequenceNo, String checkpointType, String snapshotJson) {
    }

    default void insertRecoveryDecision(AgentRecoveryDecision decision) {
        // Compatibility default for in-memory and legacy adapters.
    }

    void saveClaimed(AgentRun run, AgentRunStateTransition transition);

    void saveSucceeded(
            AgentRun run,
            long attemptId,
            long stepId,
            long providerId,
            String requestedModel,
            ModelProviderResponse response,
            String outputSnapshotJson,
            AgentRunStateTransition transition
    );

    void saveFailed(
            AgentRun run,
            long attemptId,
            long stepId,
            long providerId,
            String requestedModel,
            String errorCode,
            ResultStatus resultStatus,
            Instant availableAt,
            AgentRunStateTransition transition
    );

    record Submission(
            String tenantCode,
            long agentVersionId,
            String idempotencyKey,
            AgentRunTriggerType triggerType,
            String inputSnapshotJson,
            String requestedBy,
            Instant deadlineAt,
            Instant createdAt,
            String processInstanceId,
            String executionId,
            String activityId,
            String activityActivationId,
            String outputMappingJson,
            String processFailurePolicy,
            Integer processWaitTimeoutSeconds
    ) {
        public Submission(
                String tenantCode,
                long agentVersionId,
                String idempotencyKey,
                AgentRunTriggerType triggerType,
                String inputSnapshotJson,
                String requestedBy,
                Instant deadlineAt,
                Instant createdAt
        ) {
            this(tenantCode, agentVersionId, idempotencyKey, triggerType, inputSnapshotJson,
                    requestedBy, deadlineAt, createdAt, null, null, null, null,
                    "{}", "HOLD_FOR_OPERATIONS", null);
        }
    }
}
