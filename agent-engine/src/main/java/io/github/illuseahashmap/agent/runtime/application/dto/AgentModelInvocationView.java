package io.github.illuseahashmap.agent.runtime.application.dto;

import java.time.OffsetDateTime;

public record AgentModelInvocationView(
        long id,
        long attemptId,
        long stepId,
        String providerName,
        String requestedModel,
        String actualModel,
        String providerRequestId,
        String finishReason,
        String status,
        int inputTokens,
        int outputTokens,
        int reasoningTokens,
        Long latencyMillis,
        String errorCode,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {
}
