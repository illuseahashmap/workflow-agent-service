package io.github.illuseahashmap.agent.provider.infrastructure.openai;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderFailureKind;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Resolves and validates Provider destinations before an outbound request is made. */
@Component
public final class ProviderEndpointValidator {

    private final boolean allowPrivateNetworks;
    private final boolean allowHttp;

    @Autowired
    public ProviderEndpointValidator(
            @Value("${workflow.agent.provider.egress.allow-private-networks:false}") boolean allowPrivateNetworks,
            @Value("${workflow.agent.provider.egress.allow-http:false}") boolean allowHttp
    ) {
        this.allowPrivateNetworks = allowPrivateNetworks;
        this.allowHttp = allowHttp;
    }

    public ProviderEndpointValidator(boolean allowPrivateNetworks) {
        this(allowPrivateNetworks, true);
    }

    public void validate(URI endpoint) {
        String scheme = endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || (allowHttp && "http".equals(scheme)))) {
            throw rejected("PROVIDER_INSECURE_ENDPOINT", "Provider endpoint must use HTTPS");
        }
        if (endpoint.getUserInfo() != null || endpoint.getHost() == null || endpoint.getQuery() != null
                || endpoint.getFragment() != null) {
            throw rejected("PROVIDER_ENDPOINT_INVALID", "Provider endpoint contains unsupported URI parts");
        }
        int port = endpoint.getPort();
        if (!allowPrivateNetworks && port != -1 && port != 443 && !(allowHttp && port == 80)) {
            throw rejected("PROVIDER_ENDPOINT_PORT_REJECTED", "Provider endpoint port is not allowed");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(endpoint.getHost())) {
                if (!allowPrivateNetworks && isPrivateOrLocal(address)) {
                    throw rejected("PROVIDER_PRIVATE_ENDPOINT_REJECTED",
                            "Provider endpoint resolves to a private or local network");
                }
            }
        } catch (UnknownHostException exception) {
            throw rejected("PROVIDER_HOST_UNRESOLVED", "Provider endpoint host cannot be resolved");
        }
    }

    private boolean isPrivateOrLocal(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }

    private ModelProviderException rejected(String code, String message) {
        return new ModelProviderException(code, ModelProviderFailureKind.PERMANENT, message);
    }
}
