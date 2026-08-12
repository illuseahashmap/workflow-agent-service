package io.github.illuseahashmap.workflow.process.application.port;

import java.util.Optional;

/** Workflow-owned view of an Agent run; implementations may bridge another bounded context. */
public interface AgentCompletionRunPort {

    Optional<CompletedAgentRun> lockCompletedRun(String tenantCode, long runId);

    void markWorkflowHandled(String tenantCode, long runId);

    record CompletedAgentRun(
            long id,
            String tenantCode,
            String processInstanceId,
            String executionId,
            String activityId,
            String activityActivationId,
            Long currentAttemptId,
            String status,
            String errorCode,
            String outputSnapshotJson,
            String outputMappingJson,
            String processFailurePolicy
    ) {
    }
}
