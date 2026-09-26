package io.github.illuseahashmap.agent.provider.infrastructure.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderCapabilities;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderFailureKind;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderPort;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** LangChain4j-backed OpenAI-compatible adapter; platform contracts stay framework-neutral. */
@Component
@ConditionalOnProperty(name = "workflow.agent.provider.openai.adapter",
        havingValue = "langchain4j", matchIfMissing = true)
public class LangChain4jOpenAiModelProviderAdapter implements ModelProviderPort {

    private final ObjectMapper objectMapper;
    private final ProviderEndpointValidator endpointValidator;
    private final int maximumResponseBytes;

    public LangChain4jOpenAiModelProviderAdapter(
            ObjectMapper objectMapper,
            ProviderEndpointValidator endpointValidator,
            @Value("${workflow.agent.provider.egress.maximum-response-bytes:1048576}") int maximumResponseBytes
    ) {
        this.objectMapper = objectMapper;
        this.endpointValidator = endpointValidator;
        this.maximumResponseBytes = Math.max(1024, Math.min(maximumResponseBytes, 10 * 1024 * 1024));
    }

    @Override
    public AgentProviderType providerType() {
        return AgentProviderType.OPENAI_COMPATIBLE;
    }

    @Override
    public ModelProviderCapabilities capabilities(String baseUrl) {
        boolean responses = endpointUri(baseUrl).getPath().endsWith("/responses");
        return new ModelProviderCapabilities(
                responses ? "OPENAI_RESPONSES_COMPATIBLE" : "OPENAI_CHAT_COMPLETIONS_COMPATIBLE",
                !responses, false, false);
    }

    @Override
    public ModelProviderResponse invoke(ModelProviderRequest request) {
        validate(request);
        URI endpoint = endpointUri(request.baseUrl());
        endpointValidator.validate(endpoint);
        boolean responsesApi = endpoint.getPath().endsWith("/responses");
        long startedNanos = System.nanoTime();
        try {
            ChatResponse response = responsesApi
                    ? invokeResponses(request, endpoint)
                    : invokeChatCompletions(request, endpoint);
            long latencyMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            return toPlatformResponse(response, latencyMillis);
        } catch (BoundedLangChain4jHttpClientBuilder.ResponseTooLargeException exception) {
            throw failure("PROVIDER_RESPONSE_TOO_LARGE", ModelProviderFailureKind.PERMANENT,
                    exception.getMessage(), exception);
        } catch (TimeoutException exception) {
            throw failure("PROVIDER_TIMEOUT", ModelProviderFailureKind.TIMEOUT,
                    "Model Provider request timed out", exception);
        } catch (RateLimitException exception) {
            throw failure("PROVIDER_RATE_LIMITED", ModelProviderFailureKind.RETRYABLE,
                    "Model Provider rate limit was reached", exception);
        } catch (AuthenticationException exception) {
            throw failure("PROVIDER_AUTHENTICATION_FAILED", ModelProviderFailureKind.PERMANENT,
                    "Model Provider authentication failed", exception);
        } catch (RetriableException exception) {
            throw failure("PROVIDER_UNAVAILABLE", ModelProviderFailureKind.RETRYABLE,
                    "Model Provider is unavailable", exception);
        } catch (NonRetriableException exception) {
            throw failure("PROVIDER_REQUEST_REJECTED", ModelProviderFailureKind.PERMANENT,
                    "Model Provider rejected the request", exception);
        } catch (ModelProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure("PROVIDER_INVALID_RESPONSE", ModelProviderFailureKind.PERMANENT,
                    "Model Provider returned an invalid response", exception);
        }
    }

    private ChatResponse invokeChatCompletions(ModelProviderRequest request, URI endpoint) {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(apiRoot(endpoint, "/chat/completions"))
                .apiKey(request.credential())
                .modelName(request.model())
                .timeout(request.timeout())
                .maxRetries(0)
                .httpClientBuilder(new BoundedLangChain4jHttpClientBuilder(maximumResponseBytes))
                .build();
        return model.chat(chatRequest(request, true));
    }

    private ChatResponse invokeResponses(ModelProviderRequest request, URI endpoint) {
        BoundedLangChain4jHttpClientBuilder httpClient =
                new BoundedLangChain4jHttpClientBuilder(maximumResponseBytes);
        httpClient.readTimeout(request.timeout());
        OpenAiResponsesChatModel model = OpenAiResponsesChatModel.builder()
                .baseUrl(apiRoot(endpoint, "/responses"))
                .apiKey(request.credential())
                .modelName(request.model())
                .httpClientBuilder(httpClient)
                .build();
        // Ark's Responses-compatible endpoint does not currently provide dependable
        // native tool calling. The governed tool contract remains in the platform prompt,
        // while LangChain4j owns the Responses request/response protocol.
        return model.chat(chatRequest(request, false));
    }

    private ChatRequest chatRequest(ModelProviderRequest request, boolean nativeTools) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StringUtils.hasText(request.systemPrompt())) {
            messages.add(SystemMessage.from(request.systemPrompt()));
        }
        messages.add(UserMessage.from(request.userInput()));
        if (nativeTools && request.toolResult() != null) {
            ToolExecutionRequest call = ToolExecutionRequest.builder()
                    .id(request.toolResult().callId())
                    .name(request.toolResult().toolName())
                    .arguments(request.toolResult().argumentsJson())
                    .build();
            messages.add(AiMessage.from(call));
            messages.add(ToolExecutionResultMessage.from(call, request.toolResult().output()));
        }
        List<ToolSpecification> tools = nativeTools
                ? request.tools().stream().map(this::toolSpecification).toList()
                : List.of();
        return ChatRequest.builder().messages(messages).toolSpecifications(tools).build();
    }

    private ToolSpecification toolSpecification(ModelProviderRequest.ToolDefinition tool) {
        try {
            ObjectNode specification = objectMapper.createObjectNode();
            specification.put("name", tool.name());
            specification.put("description", tool.description());
            specification.set("parameters", objectMapper.readTree(tool.inputSchema()));
            return ToolSpecification.fromJson(objectMapper.writeValueAsString(specification));
        } catch (Exception exception) {
            throw failure("PROVIDER_TOOL_SCHEMA_INVALID", ModelProviderFailureKind.PERMANENT,
                    "Agent tool schema is invalid", exception);
        }
    }

    private ModelProviderResponse toPlatformResponse(ChatResponse response, long latencyMillis) {
        AiMessage message = response.aiMessage();
        ModelProviderResponse.ToolCall toolCall = null;
        if (message != null && message.hasToolExecutionRequests()) {
            ToolExecutionRequest call = message.toolExecutionRequests().getFirst();
            toolCall = new ModelProviderResponse.ToolCall(call.name(), call.arguments(), call.id());
        }
        String content = message == null ? null : message.text();
        if (!StringUtils.hasText(content) && toolCall == null) {
            throw failure("PROVIDER_EMPTY_RESPONSE", ModelProviderFailureKind.PERMANENT,
                    "Model Provider returned an empty response", null);
        }
        var usage = response.tokenUsage();
        int reasoningTokens = 0;
        if (usage instanceof OpenAiTokenUsage openAiUsage
                && openAiUsage.outputTokensDetails() != null
                && openAiUsage.outputTokensDetails().reasoningTokens() != null) {
            reasoningTokens = openAiUsage.outputTokensDetails().reasoningTokens();
        }
        return new ModelProviderResponse(
                content == null ? "" : content,
                response.modelName(),
                response.id(),
                response.finishReason() == null ? null : response.finishReason().name(),
                usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount(),
                usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount(),
                reasoningTokens,
                latencyMillis,
                toolCall);
    }

    private URI endpointUri(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.endsWith("/chat/completions") && !normalized.endsWith("/responses")) {
            normalized += "/chat/completions";
        }
        return URI.create(normalized);
    }

    private String apiRoot(URI endpoint, String suffix) {
        String value = endpoint.toString();
        return value.substring(0, value.length() - suffix.length());
    }

    private void validate(ModelProviderRequest request) {
        if (!StringUtils.hasText(request.baseUrl())) {
            throw failure("PROVIDER_BASE_URL_MISSING", ModelProviderFailureKind.PERMANENT,
                    "Model Provider Base URL is required", null);
        }
        if (!StringUtils.hasText(request.credential())) {
            throw failure("PROVIDER_CREDENTIAL_MISSING", ModelProviderFailureKind.PERMANENT,
                    "Model Provider credential is required", null);
        }
        if (!StringUtils.hasText(request.model())) {
            throw failure("PROVIDER_MODEL_MISSING", ModelProviderFailureKind.PERMANENT,
                    "Model name is required", null);
        }
    }

    private ModelProviderException failure(String code, ModelProviderFailureKind kind,
                                           String message, Throwable cause) {
        return cause == null ? new ModelProviderException(code, kind, message)
                : new ModelProviderException(code, kind, message, cause);
    }
}
