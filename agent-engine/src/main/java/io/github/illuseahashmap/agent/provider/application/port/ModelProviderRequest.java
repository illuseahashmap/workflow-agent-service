package io.github.illuseahashmap.agent.provider.application.port;

import java.time.Duration;

public record ModelProviderRequest(
        String baseUrl,
        String credential,
        String model,
        String systemPrompt,
        String userInput,
        Duration timeout,
        String traceId
) {
}
