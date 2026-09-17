package io.github.illuseahashmap.agent.provider.application.port;

public record ModelProviderResponse(
        String content,
        String actualModel,
        String providerRequestId,
        String finishReason,
        int inputTokens,
        int outputTokens,
        int reasoningTokens,
        long latencyMillis,
        ToolCall toolCall
) {
    public ModelProviderResponse(
            String content, String actualModel, String providerRequestId, String finishReason,
            int inputTokens, int outputTokens, int reasoningTokens, long latencyMillis
    ) {
        this(content, actualModel, providerRequestId, finishReason,
                inputTokens, outputTokens, reasoningTokens, latencyMillis, null);
    }

    public record ToolCall(String name, String argumentsJson, String callId) {
    }
}
