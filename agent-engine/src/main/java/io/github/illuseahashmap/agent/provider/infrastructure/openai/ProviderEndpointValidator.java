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
            resolve(endpoint.getHost());
        } catch (UnknownHostException exception) {
            throw rejected("PROVIDER_HOST_UNRESOLVED", "Provider endpoint host cannot be resolved");
        }
    }

    public InetAddress[] resolve(String hostname) throws UnknownHostException {
        return validateAddresses(InetAddress.getAllByName(hostname));
    }

    InetAddress[] validateAddresses(InetAddress[] addresses) {
        if (addresses.length == 0) {
            throw rejected("PROVIDER_HOST_UNRESOLVED", "Provider endpoint has no resolved addresses");
        }
        for (InetAddress address : addresses) {
            if (!allowPrivateNetworks && isPrivateOrLocal(address)) {
                throw rejected("PROVIDER_PRIVATE_ENDPOINT_REJECTED", "Provider address is not globally routable");
            }
        }
        return addresses.clone();
    }

    private boolean isPrivateOrLocal(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 16) {
            // Only global unicast; exclude transition, protocol-assignment and documentation ranges.
            return (first & 0xe0) != 0x20
                    || (first == 0x20 && second == 0x01 && (Byte.toUnsignedInt(bytes[2]) & 0xfe) == 0)
                    || (first == 0x20 && second == 0x01 && bytes[2] == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8)
                    || (first == 0x20 && second == 0x02)
                    || (first == 0x3f && second == 0xff && (bytes[2] & 0xf0) == 0);
        }
        int third = Byte.toUnsignedInt(bytes[2]);
        return first == 0 || first >= 224
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 192 && second == 0 && (third == 0 || third == 2))
                || (first == 192 && second == 88 && third == 99)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)
                || address.getHostAddress().equals("168.63.129.16");
    }

    private ModelProviderException rejected(String code, String message) {
        return new ModelProviderException(code, ModelProviderFailureKind.PERMANENT, message);
    }
}
