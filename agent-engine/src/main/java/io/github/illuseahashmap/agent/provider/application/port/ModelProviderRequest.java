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
        ToolResult toolResult
) {
    public ModelProviderRequest(
            String baseUrl, String credential, String model, String systemPrompt,
            String userInput, Duration timeout, String traceId
    ) {
        this(baseUrl, credential, model, systemPrompt, userInput, timeout, traceId, List.of(), null);
    }

    public ModelProviderRequest {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public record ToolDefinition(String name, String description, String inputSchema) {
    }

    public record ToolResult(String callId, String toolName, String argumentsJson, String output) {
    }
}
