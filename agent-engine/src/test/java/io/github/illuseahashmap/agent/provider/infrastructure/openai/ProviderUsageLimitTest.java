package io.github.illuseahashmap.agent.provider.infrastructure.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderFailureKind;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderPort;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

class ProviderUsageLimitTest {
    @ParameterizedTest
    @CsvSource({
        "true,/v1/responses,true",
        "true,/v1/chat/completions,true",
        "false,/v1/responses,true",
        "false,/v1/chat/completions,true",
        "true,/v1/responses,false",
        "true,/v1/chat/completions,false",
        "false,/v1/responses,false",
        "false,/v1/chat/completions,false"
    })
    void separatesSuspendedUsageFromTransientRateLimits(
            boolean framework, String endpoint, boolean suspended) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext(
                endpoint,
                exchange -> {
                    calls.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    byte[] body =
                            ("{\"error\":{\"code\":\""
                                            + (suspended ? "SetLimitExceeded" : "RateLimitExceeded")
                                            + "\",\"message\":\"sensitive account"
                                            + " details\",\"type\":\"TooManyRequests\"}}")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(429, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        try {
            var json = new ObjectMapper();
            ModelProviderPort adapter =
                    framework
                            ? new LangChain4jOpenAiModelProviderAdapter(
                                    json, new ProviderEndpointValidator(true), 1024)
                            : new OpenAiCompatibleModelProviderAdapter(
                                    json, new ProviderEndpointValidator(true), 1024);
            var request =
                    new ModelProviderRequest(
                            "http://127.0.0.1:" + server.getAddress().getPort() + endpoint,
                            "synthetic-key",
                            "model",
                            "system",
                            "input",
                            Duration.ofSeconds(3),
                            "test");
            assertThatThrownBy(() -> adapter.invoke(request))
                    .isInstanceOfSatisfying(
                            ModelProviderException.class,
                            failure -> {
                                assertThat(failure.failureKind())
                                        .isEqualTo(
                                                suspended
                                                        ? ModelProviderFailureKind.PERMANENT
                                                        : ModelProviderFailureKind.RETRYABLE);
                                if (suspended) {
                                    assertThat(failure.errorCode())
                                            .isEqualTo("PROVIDER_USAGE_LIMIT_EXCEEDED");
                                    assertThat(failure.getMessage())
                                            .doesNotContain("sensitive account details");
                                }
                            });
            assertThat(calls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void onlyAnExplicitStructuredCodeClassifiesSuspension() {
        var json = new ObjectMapper();
        for (String body :
                new String[] {
                    "SetLimitExceeded",
                    "null",
                    "{\"error\":{\"message\":\"SetLimitExceeded\"}}",
                    "{} {}",
                    "x".repeat(1024 * 1024 + 1)
                }) {
            assertThat(ProviderUsageLimit.exceeded(json, body)).isFalse();
        }
        assertThat(ProviderUsageLimit.exceeded(json, null)).isFalse();
    }
}
