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
        OffsetDateTime updatedAt,
        String toolSetJson,
        Long retrievalProfileVersionId
) {

    public AgentDefinitionVersion {
        executionMode = executionMode == null ? AgentExecutionMode.MODEL_ONLY : executionMode;
        toolSetJson = toolSetJson == null || toolSetJson.isBlank() ? "[]" : toolSetJson;
    }

    public AgentDefinitionVersion(
            Long id, String tenantCode, long definitionId, int version, AgentVersionStatus status,
            AgentExecutionMode executionMode, Long providerId, String modelName, String systemPrompt,
            int timeoutSeconds, AgentFailurePolicy failurePolicy, String inputSchema, String outputSchema,
            String createdBy, String publishedBy, OffsetDateTime publishedAt,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, String toolSetJson) {
        this(id, tenantCode, definitionId, version, status, executionMode, providerId, modelName,
                systemPrompt, timeoutSeconds, failurePolicy, inputSchema, outputSchema, createdBy,
                publishedBy, publishedAt, createdAt, updatedAt, toolSetJson, null);
    }

    public AgentDefinitionVersion(
            Long id, String tenantCode, long definitionId, int version, AgentVersionStatus status,
            AgentExecutionMode executionMode, Long providerId, String modelName, String systemPrompt,
            int timeoutSeconds, AgentFailurePolicy failurePolicy, String inputSchema, String outputSchema,
            String createdBy, String publishedBy, OffsetDateTime publishedAt,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, tenantCode, definitionId, version, status, executionMode, providerId, modelName,
                systemPrompt, timeoutSeconds, failurePolicy, inputSchema, outputSchema, createdBy,
                publishedBy, publishedAt, createdAt, updatedAt, "[]", null);
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
                inputSchema, outputSchema, createdBy, publishedBy, publishedAt, createdAt, updatedAt, "[]", null);
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
                publishedAt, createdAt, updatedAt, "[]", null);
    }

    public boolean published() {
        return status == AgentVersionStatus.PUBLISHED;
    }
}
