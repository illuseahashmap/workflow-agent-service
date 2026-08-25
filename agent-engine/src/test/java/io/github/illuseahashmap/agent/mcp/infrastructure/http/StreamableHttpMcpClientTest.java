package io.github.illuseahashmap.agent.mcp.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.mcp.application.port.McpCredentialResolver;
import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StreamableHttpMcpClientTest {

    @Test
    void rejectsNonHttpsMcpEndpointBeforeNetworkCall() {
        StreamableHttpMcpClient client = new StreamableHttpMcpClient(new ObjectMapper(),
                (McpCredentialResolver) (tenant, reference) -> null);
        McpConnectorVersion connector = new McpConnectorVersion(1L, "tenant-a", 1L, 1,
                "http://127.0.0.1:8080/mcp", "2025-03-26", null, 10, "DRAFT");

        assertThatThrownBy(() -> client.initialize(connector, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }
}
