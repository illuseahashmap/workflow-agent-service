package io.github.illuseahashmap.workflow.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelPort;
import io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Ark's multimodal embedding wire format is not OpenAI's /embeddings format. */
public final class VolcengineArkEmbeddingModelAdapter implements EmbeddingModelPort {
    public static final String DEFAULT_QUERY_INSTRUCTION =
            "Target_modality: text.\nInstruction:为这个句子生成表示以用于检索相关文章\nQuery:";
    public static final String DEFAULT_CORPUS_INSTRUCTION =
            "Instruction:Compress the text into one word.\nQuery:";

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final String modelVersion;
    private final int dimension;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final String queryInstruction;
    private final String corpusInstruction;
    private final io.github.illuseahashmap.agent.provider.infrastructure.openai.ProviderEndpointValidator endpointValidator;

    public VolcengineArkEmbeddingModelAdapter(HttpClient client, ObjectMapper mapper,
            URI baseUri, String apiKey, String model, String modelVersion, int dimension,
            Duration timeout, int maxResponseBytes, String queryInstruction, String corpusInstruction,
            io.github.illuseahashmap.agent.provider.infrastructure.openai.ProviderEndpointValidator endpointValidator) {
        this.endpointValidator = Objects.requireNonNull(endpointValidator, "endpointValidator");
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        String base = Objects.requireNonNull(baseUri, "baseUri").toString().replaceAll("/+$", "");
        if (!base.endsWith("/api/v3")) {
            throw new IllegalArgumentException("Ark multimodal embedding base URL must end in /api/v3");
        }
        this.endpoint = URI.create(base + "/embeddings/multimodal");
        this.apiKey = requireText(apiKey, "apiKey");
        this.model = requireText(model, "model");
        this.modelVersion = requireText(modelVersion, "modelVersion");
        if (dimension < 1 || dimension > 4096 || timeout.isZero() || timeout.isNegative()
                || maxResponseBytes < 1024 || maxResponseBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Ark embedding dimension, timeout or response limit is invalid");
        }
        this.dimension = dimension;
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        this.queryInstruction = queryInstruction == null || queryInstruction.isBlank()
                ? DEFAULT_QUERY_INSTRUCTION : queryInstruction;
        this.corpusInstruction = corpusInstruction == null || corpusInstruction.isBlank()
                ? DEFAULT_CORPUS_INSTRUCTION : corpusInstruction;
    }

    @Override
    public Batch embed(List<String> texts) {
        return embedWithInstruction(texts, corpusInstruction);
    }

    @Override
    public Batch embedQuery(List<String> queries) {
        return embedWithInstruction(queries, queryInstruction);
    }

    private Batch embedWithInstruction(List<String> texts, String instruction) {
        Objects.requireNonNull(texts, "texts");
        Instant deadline = Instant.now().plus(timeout);
        List<List<Float>> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Embedding text must not be blank");
            }
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isZero() || remaining.isNegative()) {
                throw new EmbeddingModelException("ARK_EMBEDDING_TIMEOUT", true);
            }
            vectors.add(sendWithRetry(text, instruction, deadline));
        }
        return new Batch(modelVersion, dimension, vectors);
    }

    private List<Float> sendWithRetry(String text, String instruction, Instant deadline) {
        for (int attempt = 0; ; attempt++) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isZero() || remaining.isNegative()) {
                throw new EmbeddingModelException("ARK_EMBEDDING_TIMEOUT", true);
            }
            try {
                return send(text, instruction, remaining);
            } catch (EmbeddingModelException exception) {
                if (!exception.retryable() || attempt >= 1
                        || Duration.between(Instant.now(), deadline).compareTo(Duration.ofMillis(250)) <= 0) {
                    throw exception;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new EmbeddingModelException("ARK_EMBEDDING_INTERRUPTED", true, interrupted);
                }
            }
        }
    }

    private List<Float> send(String text, String instruction, Duration remaining) {
        try {
            byte[] requestBody = mapper.writeValueAsBytes(Map.of(
                    "model", model, "dimensions", dimension, "encoding_format", "float",
                    "instructions", instruction,
                    "input", List.of(Map.of("type", "text", "text", text))));
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(remaining)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody)).build();
            HttpResponse<java.io.InputStream> response = io.github.illuseahashmap.agent.provider.infrastructure.openai
                    .GovernedHttpTransport.send(request, requestBody, client, endpointValidator, maxResponseBytes);
            try (var body = response.body()) {
                if (response.statusCode() != 200) {
                    int status = response.statusCode();
                    if (status == 401 || status == 403) {
                        throw new EmbeddingModelException("ARK_EMBEDDING_AUTHENTICATION", false);
                    }
                    throw new EmbeddingModelException(status == 429 ? "ARK_EMBEDDING_RATE_LIMITED"
                            : status >= 500 ? "ARK_EMBEDDING_UNAVAILABLE" : "ARK_EMBEDDING_REQUEST_INVALID",
                            status == 429 || status >= 500);
                }
                byte[] bytes = body.readNBytes(maxResponseBytes + 1);
                if (bytes.length > maxResponseBytes) {
                    throw new EmbeddingModelException("ARK_EMBEDDING_RESPONSE_TOO_LARGE", false);
                }
                JsonNode vector = mapper.readTree(new String(bytes, StandardCharsets.UTF_8))
                        .path("data").path("embedding");
                if (!vector.isArray() || vector.size() != dimension) {
                    throw new EmbeddingModelException("ARK_EMBEDDING_INVALID_DIMENSION", false);
                }
                List<Float> result = new ArrayList<>(dimension);
                vector.forEach(value -> {
                    if (!value.isNumber() || !Float.isFinite(value.floatValue())) {
                        throw new EmbeddingModelException("ARK_EMBEDDING_INVALID_VECTOR", false);
                    }
                    result.add(value.floatValue());
                });
                return result;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EmbeddingModelException("ARK_EMBEDDING_INTERRUPTED", true, exception);
        } catch (HttpTimeoutException exception) {
            throw new EmbeddingModelException("ARK_EMBEDDING_TIMEOUT", true, exception);
        } catch (JsonProcessingException exception) {
            throw new EmbeddingModelException("ARK_EMBEDDING_PROTOCOL_ERROR", false, exception);
        } catch (io.github.illuseahashmap.agent.provider.application.port.ModelProviderException exception) {
            throw new EmbeddingModelException("ARK_EMBEDDING_ENDPOINT_REJECTED", false, exception);
        } catch (io.github.illuseahashmap.agent.provider.infrastructure.openai.GovernedHttpTransport.ResponseTooLargeException exception) {
            throw new EmbeddingModelException("ARK_EMBEDDING_RESPONSE_TOO_LARGE", false, exception);
        } catch (IOException exception) {
            throw new EmbeddingModelException("ARK_EMBEDDING_UNAVAILABLE", true, exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
