package io.github.illuseahashmap.agent.mcp.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.mcp.application.port.McpClientException;
import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import java.io.ByteArrayInputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LangChain4jMcpClientAdapterTest {

    private MockWebServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void rejectsNonHttpsBeforeCreatingAClient() {
        var client = client(null, 1_000_000);
        McpConnectorVersion connector = connector("http://127.0.0.1:8080/mcp");

        assertThatThrownBy(() -> client.initialize(connector, Duration.ofSeconds(1)))
                .isInstanceOf(McpClientException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void initializesDiscoversAndCallsThroughLangChain4j() throws Exception {
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
        ObjectMapper mapper = new ObjectMapper();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                JsonNode body;
                try {
                    body = mapper.readTree(request.getBody().readUtf8());
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                String method = body.path("method").asText();
                JsonNode id = body.path("id");
                if ("notifications/initialized".equals(method)) {
                    return new MockResponse().setResponseCode(202);
                }
                String result = switch (method) {
                case "initialize" -> "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                        + "\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}";
                case "tools/list" -> "{\"tools\":[{\"name\":\"word_count\","
                        + "\"description\":\"Count a word\",\"inputSchema\":{\"type\":\"object\","
                        + "\"properties\":{\"word\":{\"type\":\"string\"}}}}]}";
                case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"count\\\":3}\"}],"
                        + "\"isError\":false}";
                default -> throw new IllegalStateException("Unexpected method " + method);
                };
                return new MockResponse().setHeader("Content-Type", "application/json")
                        .setHeader("Mcp-Session-Id", "remote-session")
                        .setBody("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
            }
        });
        server.start();
        LangChain4jMcpClientAdapter client = client(trustOnly(certificate), 1_000_000);
        McpConnectorVersion connector = connector(server.url("/mcp").toString());

        var session = client.initialize(connector, Duration.ofSeconds(5));
        var reused = client.initialize(connector, Duration.ofSeconds(5));
        var tools = client.listTools(session, Duration.ofSeconds(5));
        var result = client.callTool(session, "word_count", Map.of("word", "agent"),
                Duration.ofSeconds(5));

        assertThat(reused).isEqualTo(session);
        assertThat(tools).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("word_count");
            assertThat(tool.inputSchema()).contains("word");
        });
        assertThat(result.output()).contains("count", "3");
        assertThat(result.isError()).isFalse();
        assertThat(server.getRequestCount()).isEqualTo(4);
        client.closeAll();
    }

    private LangChain4jMcpClientAdapter client(SSLContext sslContext, int maximumBytes) {
        return new LangChain4jMcpClientAdapter(new ObjectMapper(),
                (tenant, reference) -> "Bearer test", maximumBytes, 200, 16, 300, sslContext);
    }

    private McpConnectorVersion connector(String endpoint) {
        return new McpConnectorVersion(1L, "tenant-a", 1L, 1, endpoint,
                "2025-11-25", "credential", 5, "PUBLISHED");
    }

    private SSLContext trustOnly(HeldCertificate certificate) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Certificate trusted = factory.generateCertificate(new ByteArrayInputStream(
                certificate.certificatePem().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        java.security.KeyStore keyStore = java.security.KeyStore.getInstance(
                java.security.KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setCertificateEntry("mcp-test", trusted);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(keyStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), new java.security.SecureRandom());
        return context;
    }
}
