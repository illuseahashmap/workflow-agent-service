package io.github.illuseahashmap.agent.mcp.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.mcp.application.port.McpCredentialResolver;
import io.github.illuseahashmap.agent.mcp.application.port.McpClientException;
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

    @Test
    void matchesJsonRpcResponseByIdAndResponseShape() throws Exception {
        StreamableHttpMcpClient client = client();

        var response = client.parseResponseBody("application/json",
                "[{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"server/request\"},"
                        + "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}]", 7);

        assertThat(response.path("result").path("ok").asBoolean()).isTrue();
    }

    @Test
    void matchesSseResponseAmongNotificationsAndMultipleEvents() throws Exception {
        StreamableHttpMcpClient client = client();

        var response = client.parseResponseBody("text/event-stream",
                "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"id\":8,\"result\":{\"tools\":[]}}\n\n", 8);

        assertThat(response.path("result").path("tools").isArray()).isTrue();
    }

    @Test
    void rejectsResponseWithMismatchedId() {
        StreamableHttpMcpClient client = client();

        assertThatThrownBy(() -> client.parseResponseBody("application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{}}", 8))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("id");
    }

    private StreamableHttpMcpClient client() {
        return new StreamableHttpMcpClient(new ObjectMapper(),
                (McpCredentialResolver) (tenant, reference) -> null);
    }
}
