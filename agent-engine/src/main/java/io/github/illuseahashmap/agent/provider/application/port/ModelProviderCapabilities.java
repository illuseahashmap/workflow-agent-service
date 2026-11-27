package io.github.illuseahashmap.agent.provider.application.port;

/**
 * Capabilities exposed by a provider adapter.  Runtime orchestration uses this
 * contract instead of inferring protocol behaviour from credentials or URL
 * strings.
 */
public record ModelProviderCapabilities(
        String protocol,
        boolean nativeToolCalling,
        boolean structuredOutput,
        boolean streaming
) {
    public ModelProviderCapabilities {
        protocol = protocol == null || protocol.isBlank() ? "unknown" : protocol;
    }

    public static ModelProviderCapabilities textFallback(String protocol) {
        return new ModelProviderCapabilities(protocol, false, false, false);
    }
}
