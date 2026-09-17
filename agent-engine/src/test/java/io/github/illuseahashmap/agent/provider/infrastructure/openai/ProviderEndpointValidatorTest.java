package io.github.illuseahashmap.agent.provider.infrastructure.openai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ProviderEndpointValidatorTest {

    private final ProviderEndpointValidator validator = new ProviderEndpointValidator(false, false);

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
