package io.github.illuseahashmap.agent.provider.infrastructure.openai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ProviderEndpointValidatorTest {

    private final ProviderEndpointValidator validator = new ProviderEndpointValidator(false, false);

    @Test
    void rejectsNonPublicIpv4Ipv6AndTransitionAddresses() {
        for (String address : java.util.List.of("100.64.0.1", "100.127.255.254", "198.18.0.1",
                "192.0.2.1", "198.51.100.1", "203.0.113.1", "240.0.0.1", "168.63.129.16",
                "[fc00::1]", "[fd12::123]", "[fe80::1]", "[2001:db8::1]", "[2002:7f00:1::1]",
                "[64:ff9b::7f00:1]", "[::ffff:127.0.0.1]")) {
            assertRejected("https://" + address + "/v1", "PROVIDER_PRIVATE_ENDPOINT_REJECTED");
        }
    }

    @Test
    void rejectsEntireDnsAnswerWhenOneAddressIsPrivate() throws Exception {
        var publicAddress = java.net.InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
        var privateAddress = java.net.InetAddress.getByAddress(new byte[] {10, 0, 0, 1});
        assertThatThrownBy(() -> validator.validateAddresses(new java.net.InetAddress[] {publicAddress, privateAddress}))
                .isInstanceOf(ModelProviderException.class);
        org.assertj.core.api.Assertions.assertThat(validator.validateAddresses(new java.net.InetAddress[] {publicAddress}))
                .containsExactly(publicAddress);
    }

    @Test
    void rejectsLoopbackMetadataPrivateNetworkAndUnexpectedPorts() {
        assertRejected("https://127.0.0.1/v1", "PROVIDER_PRIVATE_ENDPOINT_REJECTED");
        assertRejected("https://169.254.169.254/latest/meta-data", "PROVIDER_PRIVATE_ENDPOINT_REJECTED");
        assertRejected("https://10.0.0.1/v1", "PROVIDER_PRIVATE_ENDPOINT_REJECTED");
        assertRejected("https://example.com:8443/v1", "PROVIDER_ENDPOINT_PORT_REJECTED");
    }

    @Test
    void rejectsInsecureAndAmbiguousUris() {
        assertRejected("http://example.com/v1", "PROVIDER_INSECURE_ENDPOINT");
        assertRejected("https://user@example.com/v1", "PROVIDER_ENDPOINT_INVALID");
        assertRejected("https://example.com/v1?target=internal", "PROVIDER_ENDPOINT_INVALID");
    }

    private void assertRejected(String endpoint, String errorCode) {
        assertThatThrownBy(() -> validator.validate(URI.create(endpoint)))
                .isInstanceOfSatisfying(ModelProviderException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.errorCode())
                                .isEqualTo(errorCode));
    }
}
