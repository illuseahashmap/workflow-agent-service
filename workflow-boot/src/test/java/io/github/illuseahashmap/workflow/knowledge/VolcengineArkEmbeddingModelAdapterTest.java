package io.github.illuseahashmap.workflow.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VolcengineArkEmbeddingModelAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void usesArkMultimodalProtocolAndDistinctCorpusQueryInstructions() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new CopyOnWriteArrayList<>();
        server.createContext("/api/v3/embeddings/multimodal", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"data\":{\"embedding\":[0.5,0.25]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        });
        server.start();
        try {
            var adapter = adapter(server.getAddress().getPort(), 2, 1024);
            assertThat(adapter.embed(List.of("document")).vectors()).containsExactly(List.of(0.5f, 0.25f));
            assertThat(adapter.embedQuery(List.of("question")).modelVersion()).isEqualTo("ark:v1");
            assertThat(requests).hasSize(2);
            assertThat(mapper.readTree(requests.get(0)).path("instructions").asText())
                    .isEqualTo(VolcengineArkEmbeddingModelAdapter.DEFAULT_CORPUS_INSTRUCTION);
            assertThat(mapper.readTree(requests.get(1)).path("instructions").asText())
                    .isEqualTo(VolcengineArkEmbeddingModelAdapter.DEFAULT_QUERY_INSTRUCTION);
            assertThat(mapper.readTree(requests.get(1)).path("input").get(0).path("text").asText())
                    .isEqualTo("question");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsWrongDimensionsWithoutReturningCorruptIndexData() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/embeddings/multimodal", exchange -> {
            byte[] body = "{\"data\":{\"embedding\":[0.5]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        });
        server.start();
        try {
            var adapter = adapter(server.getAddress().getPort(), 2, 1024);
            assertThatThrownBy(() -> adapter.embed(List.of("document")))
                    .isInstanceOf(EmbeddingModelException.class).hasMessage("ARK_EMBEDDING_INVALID_DIMENSION");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesRateLimitOnceWithinTotalBudget() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/v3/embeddings/multimodal", exchange -> {
            int status = calls.incrementAndGet() == 1 ? 429 : 200;
            byte[] body = status == 200 ? "{\"data\":{\"embedding\":[0.5,0.25]}}".getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        });
        server.start();
        try {
            assertThat(adapter(server.getAddress().getPort(), 2, 1024).embedQuery(List.of("question"))
                    .vectors()).hasSize(1);
            assertThat(calls.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotRetryAuthenticationFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/v3/embeddings/multimodal", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> adapter(server.getAddress().getPort(), 2, 1024)
                    .embedQuery(List.of("question")))
                    .isInstanceOf(EmbeddingModelException.class)
                    .hasMessage("ARK_EMBEDDING_AUTHENTICATION")
                    .satisfies(error -> assertThat(((EmbeddingModelException) error).retryable()).isFalse());
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private VolcengineArkEmbeddingModelAdapter adapter(int port, int dimension, int maxResponseBytes) {
        return new VolcengineArkEmbeddingModelAdapter(HttpClient.newHttpClient(), mapper,
                URI.create("http://127.0.0.1:" + port + "/api/v3"), "test-key", "test-model",
                "ark:v1", dimension, Duration.ofSeconds(5), maxResponseBytes, null, null,
                new io.github.illuseahashmap.agent.provider.infrastructure.openai.ProviderEndpointValidator(true, true));
    }
}
