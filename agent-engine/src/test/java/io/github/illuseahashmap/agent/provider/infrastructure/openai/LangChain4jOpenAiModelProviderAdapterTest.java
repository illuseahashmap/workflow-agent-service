package io.github.illuseahashmap.agent.provider.infrastructure.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LangChain4jOpenAiModelProviderAdapterTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsChatCompletionAndToolSchemaThroughPlatformContract() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(200, """
                {"id":"request-1","model":"actual-model",
                 "choices":[{"message":{"role":"assistant","content":null,
                   "tool_calls":[{"id":"call-1","type":"function",
                     "function":{"name":"employee_directory","arguments":"{\\"name\\":\\"张三\\"}"}}]},
                   "finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":12,"completion_tokens":4}}
                """, requestBody);
        var adapter = adapter(1024 * 1024);
        var request = request(List.of(new ModelProviderRequest.ToolDefinition(
                "employee_directory", "Read employee", """
                {"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}
                """)));

        var response = adapter.invoke(request);

        assertThat(response.providerRequestId()).isEqualTo("request-1");
        assertThat(response.actualModel()).isEqualTo("actual-model");
        assertThat(response.toolCall().name()).isEqualTo("employee_directory");
        assertThat(response.toolCall().argumentsJson()).contains("张三");
        assertThat(response.inputTokens()).isEqualTo(12);
        assertThat(requestBody.get()).contains("employee_directory", "Read employee", "messages");
    }

    @Test
    void mapsResponsesApiThroughLangChain4jWithoutExposingNativeTools() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer("/v1/responses", 200, """
                {"id":"response-1","model":"actual-model","status":"completed",
                 "output":[{"type":"message","role":"assistant","content":[
                   {"type":"output_text","text":"connected","annotations":[]}
                 ]}],
                 "usage":{"input_tokens":10,"output_tokens":3,"total_tokens":13,
                   "output_tokens_details":{"reasoning_tokens":1}}}
                """, requestBody);
        var request = request("/v1/responses", List.of(new ModelProviderRequest.ToolDefinition(
                "employee_directory", "Read employee", """
                {"type":"object","properties":{"name":{"type":"string"}}}
                """)));

        var response = adapter(1024 * 1024).invoke(request);

        assertThat(response.content()).isEqualTo("connected");
        assertThat(response.providerRequestId()).isEqualTo("response-1");
        assertThat(response.actualModel()).isEqualTo("actual-model");
        assertThat(response.inputTokens()).isEqualTo(10);
        assertThat(response.outputTokens()).isEqualTo(3);
        assertThat(response.reasoningTokens()).isEqualTo(1);
        assertThat(requestBody.get()).contains("\"input\"", "system prompt", "user input");
        assertThat(requestBody.get()).doesNotContain("employee_directory", "\"tools\"");
        assertThat(adapter(1024 * 1024).capabilities(request.baseUrl()).nativeToolCalling()).isFalse();
    }

    @Test
    void keepsThePlatformResponseSizeLimit() throws Exception {
        startServer(200, "x".repeat(2048), new AtomicReference<>());

        assertThatThrownBy(() -> adapter(1024).invoke(request(List.of())))
                .isInstanceOfSatisfying(ModelProviderException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo("PROVIDER_RESPONSE_TOO_LARGE"));
    }

    private LangChain4jOpenAiModelProviderAdapter adapter(int maximumBytes) {
        return new LangChain4jOpenAiModelProviderAdapter(
                new ObjectMapper(), new ProviderEndpointValidator(true), maximumBytes);
    }

    private ModelProviderRequest request(List<ModelProviderRequest.ToolDefinition> tools) {
        return request("/v1", tools);
    }

    private ModelProviderRequest request(String path, List<ModelProviderRequest.ToolDefinition> tools) {
        return new ModelProviderRequest(
                "http://127.0.0.1:" + server.getAddress().getPort() + path,
                "secret", "test-model", "system prompt", "user input",
                Duration.ofSeconds(3), "trace-1", tools, null);
    }

    private void startServer(int status, String body, AtomicReference<String> requestBody) throws IOException {
        startServer("/v1/chat/completions", status, body, requestBody);
    }

    private void startServer(String path, int status, String body,
                             AtomicReference<String> requestBody) throws IOException {
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
