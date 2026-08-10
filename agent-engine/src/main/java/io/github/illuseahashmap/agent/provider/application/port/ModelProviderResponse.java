package io.github.illuseahashmap.agent.provider.application.port;

public record ModelProviderResponse(
        String content,
        String actualModel,
        String providerRequestId,
        String finishReason,
        int inputTokens,
        int outputTokens,
        int reasoningTokens,
        long latencyMillis
) {
}
