package io.github.illuseahashmap.agent.mcp.infrastructure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import io.github.illuseahashmap.agent.mcp.application.port.McpClientException;
import io.github.illuseahashmap.agent.mcp.application.port.McpClientPort;
import io.github.illuseahashmap.agent.mcp.application.port.McpCredentialResolver;
import io.github.illuseahashmap.agent.mcp.application.port.McpFailureKind;
import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** LangChain4j owns MCP protocol handling; the platform retains credentials and bounded lifecycle. */
@Component
@ConditionalOnProperty(name = "workflow.agent.mcp.client",
        havingValue = "langchain4j", matchIfMissing = true)
public class LangChain4jMcpClientAdapter implements McpClientPort {

    private final ObjectMapper objectMapper;
    private final McpCredentialResolver credentialResolver;
    private final int maximumResponseBytes;
    private final int maximumTools;
    private final int maximumSessions;
    private final Duration sessionTtl;
    private final SSLContext sslContext;
    private final ExecutorService operationExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, ClientHolder> clients = new LinkedHashMap<>(16, 0.75f, true);

    @Autowired
    public LangChain4jMcpClientAdapter(
            ObjectMapper objectMapper,
            McpCredentialResolver credentialResolver,
            @Value("${workflow.agent.mcp.maximum-response-bytes:1000000}") int maximumResponseBytes,
            @Value("${workflow.agent.mcp.maximum-tools:200}") int maximumTools,
            @Value("${workflow.agent.mcp.session-cache.max-entries:256}") int maximumSessions,
            @Value("${workflow.agent.mcp.session-cache.ttl-seconds:300}") long sessionTtlSeconds
    ) {
        this(objectMapper, credentialResolver, maximumResponseBytes, maximumTools,
                maximumSessions, sessionTtlSeconds, null);
    }

    LangChain4jMcpClientAdapter(
            ObjectMapper objectMapper,
            McpCredentialResolver credentialResolver,
            int maximumResponseBytes,
            int maximumTools,
            int maximumSessions,
            long sessionTtlSeconds,
            SSLContext sslContext
    ) {
        this.objectMapper = objectMapper;
        this.credentialResolver = credentialResolver;
        this.maximumResponseBytes = Math.max(1024, Math.min(maximumResponseBytes, 10 * 1024 * 1024));
        this.maximumTools = Math.max(1, Math.min(maximumTools, 1000));
        this.maximumSessions = Math.max(1, Math.min(maximumSessions, 10_000));
        this.sessionTtl = Duration.ofSeconds(Math.max(1, Math.min(sessionTtlSeconds, 86_400)));
        this.sslContext = sslContext;
    }

    @Override
    public Session initialize(McpConnectorVersion connector, Duration timeout) {
        requireHttps(connector.endpointUrl());
        String authorization = credentialResolver.resolveAuthorization(
                connector.tenantCode(), connector.credentialRef());
        String fingerprint = fingerprint(authorization);
        String connectorKey = connectorKey(connector, fingerprint);
        synchronized (clients) {
            evictExpired();
            for (ClientHolder holder : clients.values()) {
                if (holder.connectorKey().equals(connectorKey)) {
                    holder.touch(sessionTtl);
                    return holder.session();
                }
            }
        }
        try {
            BoundedStreamableHttpMcpTransport transport = new BoundedStreamableHttpMcpTransport(
                    connector.endpointUrl(), StringUtils.hasText(authorization)
                    ? Map.of("Authorization", authorization) : Map.of(),
                    timeout, maximumResponseBytes, sslContext);
            DefaultMcpClient.Builder builder = DefaultMcpClient.builder()
                    .key(connectorKey)
                    .transport(transport)
                    .initializationTimeout(timeout)
                    .protocolDetectionTimeout(timeout)
                    .toolExecutionTimeout(timeout)
                    .cacheToolList(true);
            if (StringUtils.hasText(connector.protocolVersion())) {
                builder.protocolVersion(connector.protocolVersion());
            }
            McpClient client = builder.build();
            Session session = new Session(connector, UUID.randomUUID().toString(),
                    connector.protocolVersion(), fingerprint);
            synchronized (clients) {
                evictExpired();
                clients.put(session.sessionId(), new ClientHolder(
                        connectorKey, session, client, Instant.now().plus(sessionTtl)));
                evictOverflow();
            }
            return session;
        } catch (RuntimeException exception) {
            throw translate(exception, "MCP initialization failed");
        }
    }

    @Override
    public List<Tool> listTools(Session session, Duration timeout) {
        ClientHolder holder = requireCurrent(session);
        try {
            List<dev.langchain4j.agent.tool.ToolSpecification> remote = within(
                    session, timeout, holder.client()::listTools);
            if (remote.size() > maximumTools) {
                throw new McpClientException("MCP_PROTOCOL_ERROR", McpFailureKind.PROTOCOL_ERROR,
                        false, "MCP tools/list exceeded the maximum tool count");
            }
            List<Tool> tools = new ArrayList<>(remote.size());
            for (var tool : remote) {
                String schema = objectMapper.readTree(tool.toJson()).path("parameters").toString();
                tools.add(new Tool(tool.name(), tool.description() == null ? "" : tool.description(), schema));
            }
            holder.touch(sessionTtl);
            return List.copyOf(tools);
        } catch (McpClientException exception) {
            invalidateBrokenSession(session, exception);
            throw exception;
        } catch (Exception exception) {
            McpClientException translated = translate(exception, "MCP tool discovery failed");
            invalidateBrokenSession(session, translated);
            throw translated;
        }
    }

    @Override
    public CallResult callTool(Session session, String toolName,
                               Map<String, Object> arguments, Duration timeout) {
        ClientHolder holder = requireCurrent(session);
        try {
            String argumentsJson = objectMapper.writeValueAsString(
                    arguments == null ? Map.of() : arguments);
            var executionRequest = ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .name(toolName)
                    .arguments(argumentsJson)
                    .build();
            var result = within(session, timeout,
                    () -> holder.client().executeTool(executionRequest));
            String output = result.resultText() == null ? "" : result.resultText();
            if (output.getBytes(StandardCharsets.UTF_8).length > maximumResponseBytes) {
                throw new McpClientException("MCP_RESPONSE_TOO_LARGE", McpFailureKind.PROTOCOL_ERROR,
                        false, "MCP tool result exceeded the configured size limit");
            }
            holder.touch(sessionTtl);
            return new CallResult(output, result.isError());
        } catch (McpClientException exception) {
            invalidateBrokenSession(session, exception);
            throw exception;
        } catch (Exception exception) {
            McpClientException translated = translate(exception, "MCP tool call failed");
            invalidateBrokenSession(session, translated);
            throw translated;
        }
    }

    private void invalidateBrokenSession(Session session, McpClientException failure) {
        if (failure.failureKind() == McpFailureKind.TIMEOUT
                || failure.failureKind() == McpFailureKind.UNAVAILABLE
                || failure.failureKind() == McpFailureKind.PROTOCOL_ERROR) {
            remove(session.sessionId());
        }
    }

    private ClientHolder requireCurrent(Session session) {
        String currentFingerprint = fingerprint(credentialResolver.resolveAuthorization(
                session.connector().tenantCode(), session.connector().credentialRef()));
        if (!session.credentialFingerprint().equals(currentFingerprint)) {
            remove(session.sessionId());
            throw new McpClientException("MCP_CREDENTIAL_ROTATED", McpFailureKind.UNAVAILABLE,
                    true, "MCP credential rotated; session will be re-established");
        }
        synchronized (clients) {
            evictExpired();
            ClientHolder holder = clients.get(session.sessionId());
            if (holder == null) {
                throw new McpClientException("MCP_SESSION_EXPIRED", McpFailureKind.UNAVAILABLE,
                        true, "MCP session expired; reconnect is required");
            }
            return holder;
        }
    }

    private McpClientException translate(Throwable failure, String message) {
        Throwable cause = rootCause(failure);
        if (cause instanceof TimeoutException || cause instanceof java.util.concurrent.TimeoutException
                || cause instanceof java.net.http.HttpTimeoutException) {
            return new McpClientException("MCP_TIMEOUT", McpFailureKind.TIMEOUT,
                    true, message, failure);
        }
        if (cause instanceof RateLimitException || httpStatus(cause) == 429) {
            return new McpClientException("MCP_RATE_LIMITED", McpFailureKind.RATE_LIMITED,
                    true, message, failure);
        }
        int status = httpStatus(cause);
        if (status == 401 || status == 403) {
            return new McpClientException("MCP_AUTHENTICATION", McpFailureKind.AUTHENTICATION,
                    false, message, failure);
        }
        if (cause instanceof ToolExecutionException) {
            return new McpClientException("MCP_TOOL_ERROR", McpFailureKind.TOOL_ERROR,
                    false, message, failure);
        }
        if (cause instanceof BoundedStreamableHttpMcpTransport.McpClientExceptionBridge) {
            return new McpClientException("MCP_RESPONSE_TOO_LARGE", McpFailureKind.PROTOCOL_ERROR,
                    false, message, failure);
        }
        if (status >= 500 || cause instanceof java.io.IOException) {
            return new McpClientException("MCP_UNAVAILABLE", McpFailureKind.UNAVAILABLE,
                    true, message, failure);
        }
        return new McpClientException("MCP_PROTOCOL_ERROR", McpFailureKind.PROTOCOL_ERROR,
                false, message, failure);
    }

    private <T> T within(Session session, Duration timeout, Supplier<T> operation) {
        Future<T> future = operationExecutor.submit(operation::get);
        try {
            return future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException exception) {
            future.cancel(true);
            remove(session.sessionId());
            throw new McpClientException("MCP_TIMEOUT", McpFailureKind.TIMEOUT,
                    true, "MCP operation exceeded the execution deadline", exception);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpClientException("MCP_INTERRUPTED", McpFailureKind.UNAVAILABLE,
                    true, "MCP operation was interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(exception.getCause());
        }
    }

    private int httpStatus(Throwable cause) {
        return cause instanceof HttpException http ? http.statusCode() : -1;
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private void remove(String sessionId) {
        synchronized (clients) {
            ClientHolder holder = clients.remove(sessionId);
            close(holder);
        }
    }

    private void evictExpired() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, ClientHolder>> iterator = clients.entrySet().iterator();
        while (iterator.hasNext()) {
            ClientHolder holder = iterator.next().getValue();
            if (!now.isBefore(holder.expiresAt())) {
                iterator.remove();
                close(holder);
            }
        }
    }

    private void evictOverflow() {
        while (clients.size() > maximumSessions) {
            Iterator<Map.Entry<String, ClientHolder>> iterator = clients.entrySet().iterator();
            ClientHolder holder = iterator.next().getValue();
            iterator.remove();
            close(holder);
        }
    }

    private String connectorKey(McpConnectorVersion connector, String fingerprint) {
        return connector.tenantCode() + ':' + connector.id() + ':' + connector.version() + ':' + fingerprint;
    }

    private String fingerprint(String authorization) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (authorization == null ? "" : authorization).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private void requireHttps(String endpointUrl) {
        URI endpoint = URI.create(endpointUrl);
        if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new McpClientException("MCP_ENDPOINT_NOT_HTTPS", McpFailureKind.PROTOCOL_ERROR,
                    false, "MCP endpoint must use HTTPS");
        }
    }

    private void close(ClientHolder holder) {
        if (holder == null) {
            return;
        }
        try {
            holder.client().close();
        } catch (Exception ignored) {
            // Cache cleanup must not hide the original operation result.
        }
    }

    @PreDestroy
    void closeAll() {
        synchronized (clients) {
            clients.values().forEach(this::close);
            clients.clear();
        }
        operationExecutor.close();
    }

    private static final class ClientHolder {
        private final String connectorKey;
        private final Session session;
        private final McpClient client;
        private volatile Instant expiresAt;

        private ClientHolder(String connectorKey, Session session,
                             McpClient client, Instant expiresAt) {
            this.connectorKey = connectorKey;
            this.session = session;
            this.client = client;
            this.expiresAt = expiresAt;
        }

        private String connectorKey() {
            return connectorKey;
        }

        private Session session() {
            return session;
        }

        private McpClient client() {
            return client;
        }

        private Instant expiresAt() {
            return expiresAt;
        }

        private void touch(Duration ttl) {
            expiresAt = Instant.now().plus(ttl);
        }
    }
}
