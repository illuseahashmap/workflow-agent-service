package io.github.illuseahashmap.agent.mcp.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.mcp.application.port.McpCredentialResolver;
import io.github.illuseahashmap.agent.mcp.application.port.McpClientException;
import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StreamableHttpMcpClientTest {

    private MockWebServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

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

    @Test
    void completesHttpsInitializeListAndCallWithSessionAndSse() throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder()
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName("localhost.sangfor.com.cn")
                .addSubjectAlternativeName("127.0.0.1")
                .build();
        HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
                .heldCertificate(certificate)
                .build();
        server = new MockWebServer();
        server.useHttps(serverCertificates.sslSocketFactory(), false);
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "session-1")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}"));
        server.enqueue(new MockResponse().setResponseCode(202));
        String toolJson = "{\"name\":\"employee_directory\","
                + "\"description\":\"Read only\","
                + "\"inputSchema\":{\"type\":\"object\"}}";
        String listResponse = "{\"jsonrpc\":\"2.0\",\"id\":2,"
                + "\"result\":{\"tools\":[" + toolJson + "]}}";
        String listSse = String.join("\n\n",
                "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}",
                "data: [" + listResponse + "]");
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(listSse));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"Li Si, Platform\"}]}}"));
        server.start();

        javax.net.ssl.SSLContext sslContext = trustOnly(certificate);
        StreamableHttpMcpClient client = new StreamableHttpMcpClient(new ObjectMapper(),
                (McpCredentialResolver) (tenant, reference) -> "Bearer test",
                HttpClient.newBuilder().sslContext(sslContext).proxy(new NoProxySelector()).build());
        McpConnectorVersion connector = new McpConnectorVersion(1L, "tenant-a", 1L, 1,
                server.url("/mcp").toString(), "2025-03-26", "credential", 10, "PUBLISHED");

        var session = client.initialize(connector, Duration.ofSeconds(5));
        var tools = client.listTools(session, Duration.ofSeconds(5));
        var result = client.callTool(session, "employee_directory", java.util.Map.of("name", "张三"),
                Duration.ofSeconds(5));

        assertThat(session.sessionId()).isEqualTo("session-1");
        assertThat(tools).extracting(StreamableHttpMcpClient.Tool::name)
                .containsExactly("employee_directory");
        assertThat(result.output()).contains("Li Si");
        assertThat(result.isError()).isFalse();
        var initializeRequest = server.takeRequest();
        var notificationRequest = server.takeRequest();
        var listRequest = server.takeRequest();
        var callRequest = server.takeRequest();
        assertThat(initializeRequest.getHeader("Authorization")).isEqualTo("Bearer test");
        assertThat(notificationRequest.getHeader("Mcp-Session-Id")).isEqualTo("session-1");
        assertThat(notificationRequest.getBody().readUtf8()).contains("notifications/initialized");
        assertThat(listRequest.getBody().readUtf8()).contains("tools/list");
        assertThat(callRequest.getBody().readUtf8()).contains("tools/call");
    }

    private SSLContext trustOnly(HeldCertificate certificate) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Certificate trusted = factory.generateCertificate(new ByteArrayInputStream(
                certificate.certificatePem().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        java.security.KeyStore keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setCertificateEntry("mcp-test", trusted);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(keyStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), new java.security.SecureRandom());
        return context;
    }

    private static final class NoProxySelector extends ProxySelector {
        @Override
        public List<java.net.Proxy> select(URI uri) {
            return List.of(java.net.Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, java.net.SocketAddress address, IOException exception) {
            // No proxy is configured for this deterministic local test server.
        }
    }

    private StreamableHttpMcpClient client() {
        return new StreamableHttpMcpClient(new ObjectMapper(),
                (McpCredentialResolver) (tenant, reference) -> null);
    }
}
