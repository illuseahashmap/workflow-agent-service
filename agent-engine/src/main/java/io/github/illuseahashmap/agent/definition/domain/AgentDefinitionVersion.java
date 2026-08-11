package io.github.illuseahashmap.agent.definition.domain;

import java.time.OffsetDateTime;

public record AgentDefinitionVersion(
        Long id,
        String tenantCode,
        long definitionId,
        int version,
        AgentVersionStatus status,
        AgentExecutionMode executionMode,
        Long providerId,
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

    public AgentDefinitionVersion {
        executionMode = executionMode == null ? AgentExecutionMode.MODEL_ONLY : executionMode;
    }

    /** Compatibility constructor for callers that predate execution-mode dispatch. */
    public AgentDefinitionVersion(
            Long id, String tenantCode, long definitionId, int version, AgentVersionStatus status,
            Long providerId, String modelName, String systemPrompt, int timeoutSeconds,
            AgentFailurePolicy failurePolicy, String inputSchema, String outputSchema,
            String createdBy, String publishedBy, OffsetDateTime publishedAt,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, tenantCode, definitionId, version, status, AgentExecutionMode.MODEL_ONLY,
                providerId, modelName, systemPrompt, timeoutSeconds, failurePolicy,
                inputSchema, outputSchema, createdBy, publishedBy, publishedAt, createdAt, updatedAt);
    }

    /** Compatibility constructor for callers that predate the input contract. */
    public AgentDefinitionVersion(
            Long id, String tenantCode, long definitionId, int version, AgentVersionStatus status,
            Long providerId, String modelName, String systemPrompt, int timeoutSeconds,
            AgentFailurePolicy failurePolicy, String outputSchema, String createdBy, String publishedBy,
            OffsetDateTime publishedAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, tenantCode, definitionId, version, status, AgentExecutionMode.MODEL_ONLY,
                providerId, modelName, systemPrompt,
                timeoutSeconds, failurePolicy, null, outputSchema, createdBy, publishedBy,
                publishedAt, createdAt, updatedAt);
    }

    public boolean published() {
        return status == AgentVersionStatus.PUBLISHED;
    }
}
