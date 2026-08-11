package io.github.illuseahashmap.agent.definition.application.dto;

import io.github.illuseahashmap.agent.definition.domain.AgentFailurePolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AgentVersionCommand(
        Long providerId,
        @Size(max = 128) String modelName,
        @Size(max = 20000) String systemPrompt,
        @Min(1) @Max(3600) int timeoutSeconds,
        @NotNull AgentFailurePolicy failurePolicy,
        @Size(max = 20000) String inputSchema,
        @Size(max = 20000) String outputSchema
) {

    public AgentVersionCommand(
            Long providerId, String modelName, String systemPrompt, int timeoutSeconds,
            AgentFailurePolicy failurePolicy, String outputSchema) {
        this(providerId, modelName, systemPrompt, timeoutSeconds, failurePolicy, null, outputSchema);
    }
}
