package io.github.illuseahashmap.agent.runtime.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Trusted application command raised by the workflow adapter, not a public REST DTO. */
public record AgentFlowableRunCommand(
        @Positive long agentVersionId,
        @NotBlank String processInstanceId,
        @NotBlank String executionId,
        @NotBlank String activityId,
        String activityActivationId,
        String inputSnapshotJson,
        String outputMappingJson,
        String processFailurePolicy,
        String nodeToolSetJson,
        @NotBlank String idempotencyKey,
        String requestedBy,
        long timeoutSeconds
) {
    public AgentFlowableRunCommand {
        if (timeoutSeconds <= 0 || timeoutSeconds > 3600) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 3600");
        }
    }

    public AgentFlowableRunCommand(
            long agentVersionId, String processInstanceId, String executionId, String activityId,
            String activityActivationId, String inputSnapshotJson, String idempotencyKey,
            String requestedBy, long timeoutSeconds) {
        this(agentVersionId, processInstanceId, executionId, activityId, activityActivationId,
                inputSnapshotJson, "{}", "HOLD_FOR_OPERATIONS", idempotencyKey,
                null, requestedBy, timeoutSeconds);
    }

    public AgentFlowableRunCommand(
            long agentVersionId, String processInstanceId, String executionId, String activityId,
            String activityActivationId, String inputSnapshotJson, String outputMappingJson,
            String processFailurePolicy, String idempotencyKey, String requestedBy, long timeoutSeconds) {
        this(agentVersionId, processInstanceId, executionId, activityId, activityActivationId,
                inputSnapshotJson, outputMappingJson, processFailurePolicy, null, idempotencyKey,
                requestedBy, timeoutSeconds);
    }
}
