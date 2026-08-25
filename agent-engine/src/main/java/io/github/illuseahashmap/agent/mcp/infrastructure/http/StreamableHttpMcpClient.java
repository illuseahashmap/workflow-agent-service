package io.github.illuseahashmap.agent.mcp.infrastructure.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.mcp.application.port.McpClientPort;
import io.github.illuseahashmap.agent.mcp.application.port.McpCredentialResolver;
import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/** Minimal, bounded Streamable HTTP client for the read-only MCP slice. */
@Component
public class StreamableHttpMcpClient implements McpClientPort {

    private static final int MAX_RESPONSE_BYTES = 1_000_000;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final McpCredentialResolver credentialResolver;
    private final AtomicLong requestIds = new AtomicLong();

    public StreamableHttpMcpClient(ObjectMapper objectMapper, McpCredentialResolver credentialResolver) {
        this.objectMapper = objectMapper;
        this.credentialResolver = credentialResolver;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public Session initialize(McpConnectorVersion connector, Duration timeout) {
        requireHttps(connector.endpointUrl());
        JsonNode result = request(connector, null, "initialize", Map.of(
                "protocolVersion", connector.protocolVersion(),
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "workflow-agent-service", "version", "0.1")), timeout);
        String protocolVersion = result.path("protocolVersion").asText();
        if (protocolVersion.isBlank()) {
            throw new IllegalStateException("MCP initialize response has no protocolVersion");
        }
        String sessionId = result.path("_sessionId").asText(null);
        Session session = new Session(connector, sessionId, protocolVersion);
        notifyInitialized(session, timeout);
        return session;
    }

    @Override
    public List<Tool> listTools(Session session, Duration timeout) {
        List<Tool> tools = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> arguments = cursor == null ? Map.of() : Map.of("cursor", cursor);
            JsonNode result = request(session.connector(), session.sessionId(), "tools/list", arguments, timeout);
            JsonNode items = result.path("tools");
            if (!items.isArray() || items.size() > 200) {
                throw new IllegalStateException("MCP tools/list returned an invalid or oversized tool list");
            }
            for (JsonNode item : items) {
                String name = item.path("name").asText();
                JsonNode schema = item.path("inputSchema");
                if (name.isBlank() || !schema.isObject()) {
                    throw new IllegalStateException("MCP tool has an invalid name or inputSchema");
                }
                tools.add(new Tool(name, item.path("description").asText(""), schema.toString()));
            }
            cursor = result.path("nextCursor").asText(null);
        } while (cursor != null && !cursor.isBlank() && tools.size() <= 200);
        if (tools.size() > 200) {
            throw new IllegalStateException("MCP tools/list exceeded the maximum tool count");
        }
        return List.copyOf(tools);
    }

    @Override
    public CallResult callTool(Session session, String toolName, Map<String, Object> arguments, Duration timeout) {
        JsonNode result = request(session.connector(), session.sessionId(), "tools/call",
                Map.of("name", toolName, "arguments", arguments == null ? Map.of() : arguments), timeout);
        boolean isError = result.path("isError").asBoolean(false);
        return new CallResult(result.path("content").toString(), isError);
    }

    private JsonNode request(McpConnectorVersion connector, String sessionId, String method,
                             Map<String, Object> params, Duration timeout) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("jsonrpc", "2.0");
            payload.put("id", requestIds.incrementAndGet());
            payload.put("method", method);
            payload.put("params", params);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(connector.endpointUrl()))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            String authorization = credentialResolver.resolveAuthorization(connector.tenantCode(), connector.credentialRef());
            if (authorization != null && !authorization.isBlank()) {
                builder.header("Authorization", authorization);
            }
            if (sessionId != null && !sessionId.isBlank()) {
                builder.header("Mcp-Session-Id", sessionId);
            }
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.body().length > MAX_RESPONSE_BYTES || response.statusCode() / 100 != 2) {
                throw new IllegalStateException("MCP request failed with status " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(responseBody(response));
            if (root.has("error")) {
                throw new IllegalStateException("MCP protocol error: " + root.path("error").path("code").asText());
            }
            JsonNode result = root.path("result");
            if (result.isMissingNode()) {
                throw new IllegalStateException("MCP response has no result");
            }
            String responseSession = response.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (responseSession != null && result.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) result).put("_sessionId", responseSession);
            }
            return result;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("MCP request failed", exception);
        }
    }

    private String responseBody(HttpResponse<byte[]> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
        String body = new String(response.body(), StandardCharsets.UTF_8);
        if (!contentType.contains("text/event-stream")) {
            return body;
        }
        String lastData = null;
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\\R", -1)) {
            if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring(5).stripLeading());
            } else if (line.isBlank() && data.length() > 0) {
                lastData = data.toString();
                data.setLength(0);
            }
        }
        if (data.length() > 0) {
            lastData = data.toString();
        }
        if (lastData == null || lastData.isBlank()) {
            throw new IllegalStateException("MCP SSE response contains no data event");
        }
        return lastData;
    }

    private void notifyInitialized(Session session, Duration timeout) {
        try {
            Map<String, Object> payload = Map.of(
                    "jsonrpc", "2.0",
                    "method", "notifications/initialized",
                    "params", Map.of());
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(session.connector().endpointUrl()))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            String authorization = credentialResolver.resolveAuthorization(
                    session.connector().tenantCode(), session.connector().credentialRef());
            if (authorization != null && !authorization.isBlank()) {
                builder.header("Authorization", authorization);
            }
            if (session.sessionId() != null && !session.sessionId().isBlank()) {
                builder.header("Mcp-Session-Id", session.sessionId());
            }
            HttpResponse<Void> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2 && response.statusCode() != 202) {
                throw new IllegalStateException("MCP initialized notification failed with status "
                        + response.statusCode());
            }
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("MCP initialized notification failed", exception);
        }
    }

    private void requireHttps(String endpointUrl) {
        URI uri = URI.create(endpointUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getHost() == null || uri.getPort() == 80) {
            throw new IllegalArgumentException("MCP endpoint must be an HTTPS URL without user info");
        }
    }
}
