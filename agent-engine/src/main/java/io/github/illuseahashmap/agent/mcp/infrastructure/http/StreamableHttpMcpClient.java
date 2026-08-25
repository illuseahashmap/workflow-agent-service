package io.github.illuseahashmap.agent.mcp.infrastructure.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.illuseahashmap.agent.mcp.application.port.McpClientPort;
import io.github.illuseahashmap.agent.mcp.application.port.McpCredentialResolver;
import io.github.illuseahashmap.agent.mcp.application.port.McpClientException;
import io.github.illuseahashmap.agent.mcp.application.port.McpFailureKind;
import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import java.io.IOException;
import java.io.InputStream;
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
            throw protocol("MCP_PROTOCOL_ERROR", "MCP initialize response has no protocolVersion");
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
                throw protocol("MCP_PROTOCOL_ERROR", "MCP tools/list returned an invalid or oversized tool list");
            }
            for (JsonNode item : items) {
                String name = item.path("name").asText();
                JsonNode schema = item.path("inputSchema");
                if (name.isBlank() || !schema.isObject()) {
                    throw protocol("MCP_PROTOCOL_ERROR", "MCP tool has an invalid name or inputSchema");
                }
                tools.add(new Tool(name, item.path("description").asText(""), schema.toString()));
            }
            cursor = result.path("nextCursor").asText(null);
        } while (cursor != null && !cursor.isBlank() && tools.size() <= 200);
        if (tools.size() > 200) {
            throw protocol("MCP_PROTOCOL_ERROR", "MCP tools/list exceeded the maximum tool count");
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
            HttpResponse<InputStream> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw statusFailure(response.statusCode());
            }
            String body = readBounded(response.body());
            JsonNode root = objectMapper.readTree(responseBody(response, body));
            if (!root.isObject()) {
                throw protocol("MCP_PROTOCOL_ERROR", "MCP response must be a JSON-RPC object");
            }
            if (root.has("error")) {
                throw protocol("MCP_PROTOCOL_ERROR", "MCP response contains a JSON-RPC error");
            }
            JsonNode result = root.path("result");
            if (result.isMissingNode()) {
                throw protocol("MCP_PROTOCOL_ERROR", "MCP response has no result");
            }
            String responseSession = response.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (responseSession != null && result.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) result).put("_sessionId", responseSession);
            }
            return result;
        } catch (McpClientException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new McpClientException("MCP_PROTOCOL_ERROR", McpFailureKind.PROTOCOL_ERROR,
                    false, "MCP response was not valid JSON", exception);
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new McpClientException("MCP_TIMEOUT", McpFailureKind.TIMEOUT, true,
                    "MCP request timed out", exception);
        } catch (IOException exception) {
            throw new McpClientException("MCP_UNAVAILABLE", McpFailureKind.UNAVAILABLE, true,
                    "MCP service is unavailable", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new McpClientException("MCP_INTERRUPTED", McpFailureKind.UNAVAILABLE, true,
                    "MCP request was interrupted", exception);
        }
    }

    private String readBounded(InputStream input) throws IOException {
        try (InputStream stream = input; java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new McpClientException("MCP_RESPONSE_TOO_LARGE", McpFailureKind.PROTOCOL_ERROR,
                            false, "MCP response exceeded the maximum size");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private McpClientException statusFailure(int status) {
        if (status == 401 || status == 403) {
            return new McpClientException("MCP_AUTHENTICATION", McpFailureKind.AUTHENTICATION,
                    false, "MCP service authentication failed");
        }
        if (status == 429) {
            return new McpClientException("MCP_RATE_LIMITED", McpFailureKind.RATE_LIMITED,
                    true, "MCP service rate limit exceeded");
        }
        if (status >= 500 || status == 408) {
            return new McpClientException("MCP_UNAVAILABLE", McpFailureKind.UNAVAILABLE,
                    true, "MCP service is temporarily unavailable");
        }
        return new McpClientException("MCP_PROTOCOL_ERROR", McpFailureKind.PROTOCOL_ERROR,
                false, "MCP request failed with status " + status);
    }

    private McpClientException protocol(String code, String message) {
        return new McpClientException(code, McpFailureKind.PROTOCOL_ERROR, false, message);
    }

    private String responseBody(HttpResponse<?> response, String body) {
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
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
            throw protocol("MCP_PROTOCOL_ERROR", "MCP SSE response contains no data event");
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
                throw statusFailure(response.statusCode());
            }
        } catch (McpClientException exception) {
            throw exception;
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new McpClientException("MCP_TIMEOUT", McpFailureKind.TIMEOUT, true,
                    "MCP initialized notification timed out", exception);
        } catch (IOException exception) {
            throw new McpClientException("MCP_UNAVAILABLE", McpFailureKind.UNAVAILABLE, true,
                    "MCP initialized notification failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new McpClientException("MCP_INTERRUPTED", McpFailureKind.UNAVAILABLE, true,
                    "MCP initialized notification was interrupted", exception);
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
