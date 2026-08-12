package io.github.illuseahashmap.agent.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderFailureKind;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderPort;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

@Component
public class OpenAiCompatibleModelProviderAdapter implements ModelProviderPort {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ProviderEndpointValidator endpointValidator;
    private final int maximumResponseBytes;

    @Autowired
    public OpenAiCompatibleModelProviderAdapter(
            ObjectMapper objectMapper,
            ProviderEndpointValidator endpointValidator,
            @Value("${workflow.agent.provider.egress.maximum-response-bytes:1048576}") int maximumResponseBytes
    ) {
        this.objectMapper = objectMapper;
        this.endpointValidator = endpointValidator;
        this.maximumResponseBytes = Math.max(1024, Math.min(maximumResponseBytes, 10 * 1024 * 1024));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public OpenAiCompatibleModelProviderAdapter(ObjectMapper objectMapper) {
        // The one-argument constructor is retained for isolated adapter tests; Spring uses the strict bean above.
        this(objectMapper, new ProviderEndpointValidator(true), 1024 * 1024);
    }

    @Override
    public AgentProviderType providerType() {
        return AgentProviderType.OPENAI_COMPATIBLE;
    }

    @Override
    public ModelProviderResponse invoke(ModelProviderRequest request) {
        validate(request);
        long startedNanos = System.nanoTime();
        try {
            URI endpoint = endpointUri(request.baseUrl());
            endpointValidator.validate(endpoint);
            boolean responsesApi = isResponsesEndpoint(endpoint);
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                    .timeout(request.timeout())
                    .header("Authorization", "Bearer " + request.credential())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(request, responsesApi)))
                    .build();
            HttpResponse<InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            String responseBody;
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(maximumResponseBytes + 1);
                if (bytes.length > maximumResponseBytes) {
                    throw new ModelProviderException(
                            "PROVIDER_RESPONSE_TOO_LARGE", ModelProviderFailureKind.PERMANENT,
                            "Model Provider response exceeded the configured size limit");
                }
                responseBody = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
            long latencyMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw httpFailure(response.statusCode(), responseBody);
            }
            return parseResponse(responseBody, latencyMillis, responsesApi);
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new ModelProviderException(
                    "PROVIDER_TIMEOUT", ModelProviderFailureKind.TIMEOUT,
                    "Model Provider request timed out", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelProviderException(
                    "PROVIDER_INTERRUPTED", ModelProviderFailureKind.RETRYABLE,
                    "Model Provider request was interrupted", exception);
        } catch (IOException exception) {
            throw new ModelProviderException(
                    "PROVIDER_UNAVAILABLE", ModelProviderFailureKind.RETRYABLE,
                    "Model Provider is unavailable", exception);
        }
    }

    private String requestBody(ModelProviderRequest request, boolean responsesApi) throws IOException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        if (responsesApi) {
            if (StringUtils.hasText(request.systemPrompt())) {
                body.put("instructions", request.systemPrompt());
            }
            body.put("input", request.userInput());
            return objectMapper.writeValueAsString(body);
        }
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        if (StringUtils.hasText(request.systemPrompt())) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        messages.addObject().put("role", "user").put("content", request.userInput());
        return objectMapper.writeValueAsString(body);
    }

    private ModelProviderResponse parseResponse(String responseBody, long latencyMillis, boolean responsesApi) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return responsesApi
                    ? parseResponsesApiResponse(root, latencyMillis)
                    : parseChatCompletionResponse(root, latencyMillis);
        } catch (ModelProviderException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ModelProviderException(
                    "PROVIDER_INVALID_RESPONSE", ModelProviderFailureKind.PERMANENT,
                    "Model Provider returned an invalid response", exception);
        }
    }

    private ModelProviderResponse parseChatCompletionResponse(JsonNode root, long latencyMillis) {
            JsonNode choice = root.path("choices").path(0);
            String content = choice.path("message").path("content").asText(null);
            requireContent(content);
            JsonNode usage = root.path("usage");
            int reasoningTokens = usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0);
            return new ModelProviderResponse(
                    content,
                    root.path("model").asText(null),
                    root.path("id").asText(null),
                    choice.path("finish_reason").asText(null),
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    reasoningTokens,
                    latencyMillis);
    }

    private ModelProviderResponse parseResponsesApiResponse(JsonNode root, long latencyMillis) {
        String content = root.path("output_text").asText(null);
        if (!StringUtils.hasText(content)) {
            content = findOutputText(root.path("output"));
        }
        requireContent(content);
        JsonNode usage = root.path("usage");
        int reasoningTokens = usage.path("output_tokens_details").path("reasoning_tokens").asInt(0);
        return new ModelProviderResponse(
                content,
                root.path("model").asText(null),
                root.path("id").asText(null),
                root.path("status").asText(null),
                usage.path("input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0),
                reasoningTokens,
                latencyMillis);
    }

    private String findOutputText(JsonNode output) {
        for (JsonNode item : output) {
            for (JsonNode content : item.path("content")) {
                if ("output_text".equals(content.path("type").asText())) {
                    String text = content.path("text").asText(null);
                    if (StringUtils.hasText(text)) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    private void requireContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new ModelProviderException(
                    "PROVIDER_EMPTY_RESPONSE", ModelProviderFailureKind.PERMANENT,
                    "Model Provider returned an empty response");
        }
    }

    private ModelProviderException httpFailure(int statusCode, String responseBody) {
        ModelProviderFailureKind failureKind = statusCode == 408 || statusCode == 409
                || statusCode == 429 || statusCode >= 500
                ? ModelProviderFailureKind.RETRYABLE
                : ModelProviderFailureKind.PERMANENT;
        String errorCode = remoteErrorCode(responseBody);
        if (errorCode == null) {
            errorCode = switch (statusCode) {
            case 401, 403 -> "PROVIDER_AUTHENTICATION_FAILED";
            case 404 -> "PROVIDER_ENDPOINT_NOT_FOUND";
            case 429 -> "PROVIDER_RATE_LIMITED";
            default -> statusCode >= 500 ? "PROVIDER_UNAVAILABLE" : "PROVIDER_REQUEST_REJECTED";
            };
        }
        return new ModelProviderException(
                errorCode, failureKind, "Model Provider request failed with HTTP " + statusCode);
    }

    private String remoteErrorCode(String responseBody) {
        if (!StringUtils.hasText(responseBody)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String remoteCode = firstText(
                    root.path("error").path("code"),
                    root.path("error").path("type"),
                    root.path("code"));
            if (!StringUtils.hasText(remoteCode)) {
                return null;
            }
            String normalized = remoteCode.toUpperCase(Locale.ROOT)
                    .replaceAll("[^A-Z0-9]+", "_")
                    .replaceAll("^_+|_+$", "");
            if (!StringUtils.hasText(normalized)) {
                return null;
            }
            int maximumRemoteCodeLength = 55;
            return "PROVIDER_" + normalized.substring(0, Math.min(normalized.length(), maximumRemoteCodeLength));
        } catch (IOException exception) {
            return null;
        }
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String value = node.asText(null);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private URI endpointUri(String baseUrl) {
        String normalized = baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions") || normalized.endsWith("/responses")) {
            return URI.create(normalized);
        }
        return URI.create(normalized + "/chat/completions");
    }

    private boolean isResponsesEndpoint(URI endpoint) {
        return endpoint.getPath().endsWith("/responses");
    }

    private void validate(ModelProviderRequest request) {
        if (!StringUtils.hasText(request.baseUrl())) {
            throw new ModelProviderException(
                    "PROVIDER_BASE_URL_MISSING", ModelProviderFailureKind.PERMANENT,
                    "Model Provider Base URL is required");
        }
        if (!StringUtils.hasText(request.credential())) {
            throw new ModelProviderException(
                    "PROVIDER_CREDENTIAL_MISSING", ModelProviderFailureKind.PERMANENT,
                    "Model Provider credential is required");
        }
        if (!StringUtils.hasText(request.model())) {
            throw new ModelProviderException(
                    "PROVIDER_MODEL_MISSING", ModelProviderFailureKind.PERMANENT,
                    "Model name is required");
        }
    }
}
