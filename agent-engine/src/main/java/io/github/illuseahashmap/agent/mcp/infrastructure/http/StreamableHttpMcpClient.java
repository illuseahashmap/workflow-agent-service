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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Minimal, bounded Streamable HTTP client for the read-only MCP slice. */
@Component
public class StreamableHttpMcpClient implements McpClientPort {

    private static final int MAX_RESPONSE_BYTES = 1_000_000;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final McpCredentialResolver credentialResolver;
    private final AtomicLong requestIds = new AtomicLong();

    @Autowired
    public StreamableHttpMcpClient(ObjectMapper objectMapper, McpCredentialResolver credentialResolver) {
        this(objectMapper, credentialResolver,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    StreamableHttpMcpClient(ObjectMapper objectMapper, McpCredentialResolver credentialResolver,
                            HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.credentialResolver = credentialResolver;
        this.httpClient = httpClient;
    }

    @Override
    public Session initialize(McpConnectorVersion connector, Duration timeout) {
        requireHttps(connector.endpointUrl());
        java.time.Instant deadline = java.time.Instant.now().plus(timeout);
        JsonNode result = request(connector, null, "initialize", Map.of(
                "protocolVersion", connector.protocolVersion(),
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "workflow-agent-service", "version", "0.1")),
                remaining(deadline));
        String protocolVersion = result.path("protocolVersion").asText();
        if (protocolVersion.isBlank()) {
            throw protocol("MCP_PROTOCOL_ERROR", "MCP initialize response has no protocolVersion");
        }
        String sessionId = result.path("_sessionId").asText(null);
        Session session = new Session(connector, sessionId, protocolVersion);
        notifyInitialized(session, remaining(deadline));
        return session;
    }

    @Override
    public List<Tool> listTools(Session session, Duration timeout) {
        List<Tool> tools = new ArrayList<>();
        java.time.Instant deadline = java.time.Instant.now().plus(timeout);
        String cursor = null;
        do {
            Map<String, Object> arguments = cursor == null ? Map.of() : Map.of("cursor", cursor);
            JsonNode result = request(session.connector(), session.sessionId(), "tools/list", arguments,
                    remaining(deadline));
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
        java.time.Instant deadline = java.time.Instant.now().plus(timeout);
        JsonNode result = request(session.connector(), session.sessionId(), "tools/call",
                Map.of("name", toolName, "arguments", arguments == null ? Map.of() : arguments),
                remaining(deadline));
        boolean isError = result.path("isError").asBoolean(false);
        return new CallResult(result.path("content").toString(), isError);
    }

    private JsonNode request(McpConnectorVersion connector, String sessionId, String method,
                             Map<String, Object> params, Duration timeout) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("jsonrpc", "2.0");
            long requestId = requestIds.incrementAndGet();
            payload.put("id", requestId);
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
            try (InputStream bodyStream = response.body()) {
                if (response.statusCode() / 100 != 2) {
                    throw statusFailure(response.statusCode());
                }
                String body = readBounded(bodyStream);
                JsonNode root = parseResponse(response, body, requestId);
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
            }
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

    private JsonNode parseResponse(HttpResponse<?> response, String body, long requestId)
            throws JsonProcessingException {
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
        return parseResponseBody(contentType, body, requestId);
    }

    JsonNode parseResponseBody(String contentType, String body, long requestId)
            throws JsonProcessingException {
        if (!contentType.contains("text/event-stream")) {
            return matchingEnvelope(objectMapper.readTree(body), requestId);
        }
        JsonNode matched = null;
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\\R", -1)) {
            if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(line.substring(5).stripLeading());
            } else if (line.isBlank() && data.length() > 0) {
                matched = firstMatching(matched, objectMapper.readTree(data.toString()), requestId);
                data.setLength(0);
            }
        }
        if (data.length() > 0) {
            matched = firstMatching(matched, objectMapper.readTree(data.toString()), requestId);
        }
        if (matched == null) {
            throw protocol("MCP_PROTOCOL_ERROR", "MCP SSE response contains no data event");
        }
        return matched;
    }

    private JsonNode matchingEnvelope(JsonNode candidate, long requestId) {
        JsonNode matched = firstMatching(null, candidate, requestId);
        if (matched == null) {
            throw protocol("MCP_PROTOCOL_ERROR", "MCP response id did not match the request");
        }
        return matched;
    }

    private JsonNode firstMatching(JsonNode current, JsonNode candidate, long requestId) {
        if (current != null) {
            return current;
        }
        if (candidate.isArray()) {
            for (JsonNode item : candidate) {
                JsonNode matched = firstMatching(null, item, requestId);
                if (matched != null) {
                    return matched;
                }
            }
            return null;
        }
        if (candidate.isObject() && candidate.has("id")
                && (candidate.has("result") || candidate.has("error"))
                && candidate.path("id").asLong(Long.MIN_VALUE) == requestId) {
            return candidate;
        }
        return null;
    }

    private Duration remaining(java.time.Instant deadline) {
        Duration duration = Duration.between(java.time.Instant.now(), deadline);
        if (duration.isZero() || duration.isNegative()) {
            throw new McpClientException("MCP_TIMEOUT", McpFailureKind.TIMEOUT, true,
                    "MCP request exceeded the total timeout budget");
        }
        return duration;
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
