package io.github.illuseahashmap.workflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.illuseahashmap.agent.runtime.application.AgentRunWorkerService;
import io.github.illuseahashmap.workflow.WorkflowAgentServiceApplication;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Real TCP, authentication, CSRF, controllers, persistence and worker; deterministic model only.
 */
@SpringBootTest(
        classes = WorkflowAgentServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentBusinessHttpIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int port;

    @Autowired private AgentRunWorkerService worker;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder()
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.flyway.baseline-on-migrate", () -> "true");
        r.add("spring.flyway.baseline-version", () -> "0");
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("flowable.database-schema-update", () -> "true");
        r.add("flowable.async-executor-activate", () -> "false");
        r.add("workflow.security.enabled", () -> "false");
        r.add("workflow.runtime.environment", () -> "test");
        r.add(
                "workflow.auth.token.secret",
                () -> "isolated-business-http-token-secret-over-32-bytes");
        r.add("workflow.auth.token.cookie-secure", () -> "false");
        r.add("workflow.auth.bootstrap-admin.enabled", () -> "true");
        r.add("workflow.auth.bootstrap-admin.username", () -> "http-admin");
        r.add("workflow.auth.bootstrap-admin.password", () -> "isolated-http-test-password");
        r.add("workflow.agent.worker.enabled", () -> "false");
        r.add("workflow.agent.graph.enabled", () -> "false");
        r.add(
                "workflow.agent.credentials.master-key-base64",
                () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test
    void publishSubmitAndInspectRunAcrossRealHttp() throws Exception {
        String csrf = get("/auth/csrf").asText();
        send(
                "POST",
                "/auth/login",
                Map.of("username", "http-admin", "password", "isolated-http-test-password"),
                csrf,
                200);
        assertThat(get("/auth/me").path("username").asText()).isEqualTo("http-admin");
        long provider =
                write(
                                "POST",
                                "/agent-providers",
                                Map.of(
                                        "code",
                                        "http-mock",
                                        "name",
                                        "HTTP test model",
                                        "type",
                                        "MOCK",
                                        "defaultModel",
                                        "mock",
                                        "enabled",
                                        true))
                        .path("id")
                        .asLong();
        long definition =
                write(
                                "POST",
                                "/agents",
                                Map.of(
                                        "code",
                                        "http-agent",
                                        "name",
                                        "HTTP acceptance",
                                        "description",
                                        "isolated",
                                        "enabled",
                                        true))
                        .path("id")
                        .asLong();
        long version = get("/agents/" + definition + "/versions").get(0).path("id").asLong();
        var runtime =
                Map.of(
                        "backend",
                        "NATIVE",
                        "stateSchemaVersion",
                        2,
                        "maxModelCalls",
                        6,
                        "maxToolCalls",
                        4);
        write("PUT", "/agents/versions/" + version + "/runtime", runtime);
        write(
                "POST",
                "/agents/" + definition + "/versions/" + version,
                Map.of(
                        "executionMode",
                        "MODEL_ONLY",
                        "providerId",
                        provider,
                        "modelName",
                        "mock",
                        "systemPrompt",
                        "Return an approval object",
                        "timeoutSeconds",
                        120,
                        "failurePolicy",
                        "MANUAL_REVIEW",
                        "outputSchema",
                        "{\"type\":\"object\",\"required\":[\"decision\"]}"));
        write("POST", "/agents/" + definition + "/versions/" + version + "/publish", Map.of());
        send(
                "PUT",
                "/agents/versions/" + version + "/runtime",
                runtime,
                get("/auth/csrf").asText(),
                409);
        long run =
                write(
                                "POST",
                                "/agent-runs/manual-tests",
                                Map.of(
                                        "definitionId",
                                        definition,
                                        "input",
                                        "Synthetic HTTP acceptance"))
                        .path("runId")
                        .asLong();
        assertThat(run).isPositive();
        assertThat(worker.executeNext()).isTrue();
        assertThat(get("/agent-runs/" + run).path("run").path("status").asText())
                .isEqualTo("SUCCEEDED");
        assertThat(get("/agents/versions/" + version + "/runtime").path("backend").asText())
                .isEqualTo("NATIVE");
    }

    private JsonNode get(String path) throws Exception {
        return send("GET", path, null, null, 200);
    }

    private JsonNode write(String method, String path, Object value) throws Exception {
        return send(method, path, value, get("/auth/csrf").asText(), 200);
    }

    private JsonNode send(String method, String path, Object value, String csrf, int expected)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json");
        if (csrf != null) {
            request.header("X-XSRF-TOKEN", csrf);
        }
        request.method(
                method,
                value == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(value)));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as(method + " " + path + ": " + response.body())
                .isEqualTo(expected);
        return json.readTree(response.body()).path("data");
    }
}
