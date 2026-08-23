package io.github.illuseahashmap.agent.provider.application.port;

import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;

public interface ModelProviderPort {

    AgentProviderType providerType();

    /** Provider-specific capabilities; runtimes must not infer them from credentials. */
    default ModelProviderCapabilities capabilities(String baseUrl) {
        return ModelProviderCapabilities.textFallback("unknown");
    }

    /** Compatibility facade for callers that only need native tool calling. */
    default boolean supportsNativeToolCalling(String baseUrl) {
        return capabilities(baseUrl).nativeToolCalling();
    }

    ModelProviderResponse invoke(ModelProviderRequest request);
}
