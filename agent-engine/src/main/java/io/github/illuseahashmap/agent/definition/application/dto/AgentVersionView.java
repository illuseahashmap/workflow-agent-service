package io.github.illuseahashmap.agent.definition.application.dto;

import io.github.illuseahashmap.agent.definition.domain.AgentFailurePolicy;
import io.github.illuseahashmap.agent.definition.domain.AgentVersionStatus;
import java.time.OffsetDateTime;

public record AgentVersionView(
        long id,
        long definitionId,
        int version,
        AgentVersionStatus status,
        Long providerId,
        String providerName,
        String modelName,
        String systemPrompt,
        int timeoutSeconds,
        AgentFailurePolicy failurePolicy,
        String inputSchema,
        String outputSchema,
        String createdBy,
        String publishedBy,
        OffsetDateTime publishedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
