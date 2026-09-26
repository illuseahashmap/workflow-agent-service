package io.github.illuseahashmap.agent.mcp.infrastructure.http;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.transport.McpJson;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.McpClientMessage;
import dev.langchain4j.mcp.protocol.McpInitializationNotification;
import dev.langchain4j.mcp.protocol.McpInitializeRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Security transport for LangChain4j MCP protocol handling. It intentionally owns only
 * bounded HTTPS I/O; JSON-RPC correlation, initialization and tool semantics stay in LangChain4j.
 */
final class BoundedStreamableHttpMcpTransport implements McpTransport {

    private final URI endpoint;
    private final Map<String, String> headers;
    private final int maximumResponseBytes;
    private final HttpClient httpClient;
    private final Executor executor;
    private final AtomicReference<String> sessionId = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile McpOperationHandler operationHandler;
    private volatile boolean modernProtocol;
    private volatile String protocolVersion;
    private volatile Runnable failureHandler = () -> { };

    BoundedStreamableHttpMcpTransport(String endpoint, Map<String, String> headers,
                                      Duration timeout, int maximumResponseBytes,
                                      javax.net.ssl.SSLContext sslContext) {
        this.endpoint = URI.create(endpoint);
        this.headers = Map.copyOf(headers);
        this.maximumResponseBytes = maximumResponseBytes;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(timeout);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.httpClient = builder.build();
    }

    @Override
    public void start(McpOperationHandler handler) {
        this.operationHandler = handler;
    }

    @Override
    public CompletableFuture<String> sendInitializeRequest(McpInitializeRequest request) {
        CompletableFuture<String> initialized = sendRequest(new McpCallContext(null, request));
        return initialized.thenCompose(response -> send(
                new McpCallContext(null, new McpInitializationNotification())).thenApply(ignored -> response));
    }

    @Override
    public CompletableFuture<String> sendRequest(McpClientMessage message) {
        return sendRequest(new McpCallContext(null, message));
    }

    @Override
    public CompletableFuture<String> sendRequest(McpCallContext context) {
        return send(context);
    }

    @Override
    public void sendMessage(McpClientMessage message) {
        send(new McpCallContext(null, message));
    }

    @Override
    public void sendMessage(McpCallContext context) {
        send(context);
    }

    private CompletableFuture<String> send(McpCallContext context) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("MCP transport is closed"));
        }
        Long id = context.message().getId();
        CompletableFuture<String> result = new CompletableFuture<>();
        if (id != null) {
            operationHandler.expectResponse(id, result);
        }
        CompletableFuture.runAsync(() -> exchange(context, id, result), executor)
                .exceptionally(failure -> {
                    result.completeExceptionally(failure);
                    failureHandler.run();
                    return null;
                });
        return result;
    }

    private void exchange(McpCallContext context, Long id, CompletableFuture<String> result) {
        String payload = McpJson.serialize(context.message());
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json,text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        headers.forEach(builder::header);
        if (modernProtocol) {
            if (protocolVersion != null) {
                builder.header("MCP-Protocol-Version", protocolVersion);
            }
            String method = McpJson.parse(payload).path("method").asText(null);
            if (method != null) {
                builder.header("Mcp-Method", method);
            }
        } else if (sessionId.get() != null && !(context.message() instanceof McpInitializeRequest)) {
            builder.header("Mcp-Session-Id", sessionId.get());
        }
        if (context.mcpParamHeaders() != null) {
            context.mcpParamHeaders().forEach((name, value) -> builder.header("Mcp-Param-" + name, value));
        }
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream body = response.body()) {
                bytes = body.readNBytes(maximumResponseBytes + 1);
            }
            if (bytes.length > maximumResponseBytes) {
                throw new McpClientExceptionBridge("MCP response exceeded the configured size limit");
            }
            String body = new String(bytes, StandardCharsets.UTF_8);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new HttpException(response.statusCode(), body);
            }
            if (!modernProtocol) {
                response.headers().firstValue("Mcp-Session-Id").ifPresent(sessionId::set);
            }
            if (id == null) {
                result.complete(null);
                return;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (contentType.contains("text/event-stream")) {
                dispatchSse(body);
            } else if (!body.isBlank()) {
                operationHandler.onMessage(body);
            } else {
                result.completeExceptionally(new IllegalStateException("MCP response body is empty"));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("MCP endpoint is unavailable", exception);
        }
    }

    private void dispatchSse(String body) {
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\\R", -1)) {
            if (line.isBlank()) {
                dispatchData(data);
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring(5).stripLeading());
            }
        }
        dispatchData(data);
    }

    private void dispatchData(StringBuilder data) {
        if (!data.isEmpty()) {
            operationHandler.onMessage(data.toString());
            data.setLength(0);
        }
    }

    @Override
    public void checkHealth() {
        if (closed.get()) {
            throw new IllegalStateException("MCP transport is closed");
        }
    }

    @Override
    public void onFailure(Runnable action) {
        this.failureHandler = action == null ? () -> { } : action;
    }

    @Override
    public void setModernProtocol(boolean modernProtocol) {
        this.modernProtocol = modernProtocol;
    }

    @Override
    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    @Override
    public void close() {
        closed.set(true);
        if (executor instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Closing is best effort.
            }
        }
    }

    static final class McpClientExceptionBridge extends RuntimeException {
        private McpClientExceptionBridge(String message) {
            super(message);
        }
    }
}
