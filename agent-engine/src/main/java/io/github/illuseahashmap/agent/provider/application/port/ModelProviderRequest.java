package io.github.illuseahashmap.agent.provider.application.port;

import java.time.Duration;
import java.util.List;

public record ModelProviderRequest(
        String baseUrl,
        String credential,
        String model,
        String systemPrompt,
        String userInput,
        Duration timeout,
        String traceId,
        List<ToolDefinition> tools,
        ToolResult toolResult,
        StructuredOutput structuredOutput
) {
    public ModelProviderRequest(
            String baseUrl, String credential, String model, String systemPrompt,
            String userInput, Duration timeout, String traceId
    ) {
        this(baseUrl, credential, model, systemPrompt, userInput, timeout, traceId, List.of(), null, null);
    }

    public ModelProviderRequest(
            String baseUrl, String credential, String model, String systemPrompt,
            String userInput, Duration timeout, String traceId,
            List<ToolDefinition> tools, ToolResult toolResult
    ) {
        this(baseUrl, credential, model, systemPrompt, userInput, timeout, traceId,
                tools, toolResult, null);
    }

    public ModelProviderRequest {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public record ToolDefinition(String name, String description, String inputSchema,
                                 String capabilityType) {
        public ToolDefinition(String name, String description, String inputSchema) {
            this(name, description, inputSchema, "DIRECT");
        }

        public ToolDefinition {
            capabilityType = capabilityType == null || capabilityType.isBlank()
                    ? "DIRECT" : capabilityType;
        }
    }

    public record ToolResult(String callId, String toolName, String argumentsJson, String output) {
    }

    /** Provider-neutral request for a JSON response constrained by a named schema. */
    public record StructuredOutput(String name, String jsonSchema) {
    }
}
