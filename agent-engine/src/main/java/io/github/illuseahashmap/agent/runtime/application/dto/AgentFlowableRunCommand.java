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
        @NotBlank String idempotencyKey,
        String requestedBy,
        long timeoutSeconds
) {
    public AgentFlowableRunCommand {
        if (timeoutSeconds <= 0 || timeoutSeconds > 3600) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 3600");
        }
    }
}
