package io.github.illuseahashmap.agent.definition.domain;

import java.time.OffsetDateTime;

public record AgentDefinitionVersion(
        Long id,
        String tenantCode,
        long definitionId,
        int version,
        AgentVersionStatus status,
        Long providerId,
        String modelName,
        String systemPrompt,
        int timeoutSeconds,
        AgentFailurePolicy failurePolicy,
        String outputSchema,
        String createdBy,
        String publishedBy,
        OffsetDateTime publishedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public boolean published() {
        return status == AgentVersionStatus.PUBLISHED;
    }
}
