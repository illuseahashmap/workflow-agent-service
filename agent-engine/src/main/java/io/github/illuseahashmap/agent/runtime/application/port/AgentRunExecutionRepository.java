package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.runtime.domain.AgentRun;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStateTransition;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunTriggerType;
import java.time.Instant;
import java.util.Optional;

public interface AgentRunExecutionRepository {

    AgentRun insertQueued(Submission submission);

    Optional<AgentRunExecutionSnapshot> lockNextAvailable(Instant now);

    int recoverExpired(Instant now);

    int nextAttemptNumber(String tenantCode, long runId);

    long insertRunningAttempt(String tenantCode, long runId, int attemptNumber, Instant startedAt);

    long insertRunningStep(String tenantCode, long runId, long attemptId, Instant startedAt);

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
            String activityActivationId
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
                    requestedBy, deadlineAt, createdAt, null, null, null, null);
        }
    }
}
