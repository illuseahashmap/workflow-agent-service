package io.github.illuseahashmap.agent.provider.infrastructure.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderFailureKind;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleModelProviderAdapterTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsOpenAiCompatibleCompletionAndUsage() throws Exception {
        startServer(200, """
                {
                  "id":"request-1",
                  "model":"actual-model",
                  "choices":[{"message":{"content":"approved"},"finish_reason":"stop"}],
                  "usage":{"prompt_tokens":12,"completion_tokens":4,
                    "completion_tokens_details":{"reasoning_tokens":2}}
                }
                """);
        var adapter = new OpenAiCompatibleModelProviderAdapter(new ObjectMapper());

        var response = adapter.invoke(request());

        assertThat(response.content()).isEqualTo("approved");
        assertThat(response.actualModel()).isEqualTo("actual-model");
        assertThat(response.providerRequestId()).isEqualTo("request-1");
        assertThat(response.inputTokens()).isEqualTo(12);
        assertThat(response.outputTokens()).isEqualTo(4);
        assertThat(response.reasoningTokens()).isEqualTo(2);
    }

    @Test
    void mapsResponsesApiRequestAndResponseWhenExactEndpointIsConfigured() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer("/v1/responses", 200, """
                {
                  "id":"response-1",
                  "model":"actual-model",
                  "status":"completed",
                  "output":[{"type":"message","content":[
                    {"type":"output_text","text":"connected"}
                  ]}],
                  "usage":{"input_tokens":10,"output_tokens":3,
                    "output_tokens_details":{"reasoning_tokens":1}}
                }
                """, requestBody);
        var adapter = new OpenAiCompatibleModelProviderAdapter(new ObjectMapper());

        var response = adapter.invoke(request("/v1/responses"));

        assertThat(response.content()).isEqualTo("connected");
        assertThat(response.providerRequestId()).isEqualTo("response-1");
        assertThat(response.finishReason()).isEqualTo("completed");
        assertThat(response.inputTokens()).isEqualTo(10);
        assertThat(response.outputTokens()).isEqualTo(3);
        assertThat(response.reasoningTokens()).isEqualTo(1);
        assertThat(requestBody.get()).contains("\"instructions\":\"system prompt\"");
        assertThat(requestBody.get()).contains("\"input\":\"user input\"");
        assertThat(requestBody.get()).doesNotContain("\"messages\"");
    }

    @Test
    void classifiesServiceUnavailableAsRetryable() throws Exception {
        startServer(503, "{}");
        var adapter = new OpenAiCompatibleModelProviderAdapter(new ObjectMapper());

        assertThatThrownBy(() -> adapter.invoke(request()))
                .isInstanceOfSatisfying(ModelProviderException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
                    assertThat(exception.failureKind()).isEqualTo(ModelProviderFailureKind.RETRYABLE);
                });
    }

    @Test
    void classifiesInvalidCredentialAsPermanent() throws Exception {
        startServer(401, "{}");
        var adapter = new OpenAiCompatibleModelProviderAdapter(new ObjectMapper());

        assertThatThrownBy(() -> adapter.invoke(request()))
                .isInstanceOfSatisfying(ModelProviderException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo("PROVIDER_AUTHENTICATION_FAILED");
                    assertThat(exception.failureKind()).isEqualTo(ModelProviderFailureKind.PERMANENT);
                });
    }

    @Test
    void preservesSanitizedRemoteErrorCodeWithoutExposingResponseDetails() throws Exception {
        startServer(404, """
                {"error":{"code":"model.not-found","message":"sensitive provider detail"}}
                """);
        var adapter = new OpenAiCompatibleModelProviderAdapter(new ObjectMapper());

        assertThatThrownBy(() -> adapter.invoke(request()))
                .isInstanceOfSatisfying(ModelProviderException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo("PROVIDER_MODEL_NOT_FOUND");
                    assertThat(exception.getMessage()).doesNotContain("sensitive provider detail");
                });
    }

    private ModelProviderRequest request() {
        return request("/v1");
    }

    private ModelProviderRequest request(String path) {
        return new ModelProviderRequest(
                "http://127.0.0.1:" + server.getAddress().getPort() + path,
                "secret",
                "test-model",
                "system prompt",
                "user input",
                Duration.ofSeconds(3),
                "trace-1");
    }

    private void startServer(int status, String body) throws IOException {
        startServer("/v1/chat/completions", status, body, new AtomicReference<>());
    }

    private void startServer(
            String path, int status, String body, AtomicReference<String> requestBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, status, body);
        });
        server.start();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
