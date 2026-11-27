package io.github.illuseahashmap.agent.runtime.application.impl;

import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.runtime.application.port.AgentResultPolicy;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Baseline deterministic result policy for MODEL_ONLY execution. */
@Component
public class DefaultAgentResultPolicy implements AgentResultPolicy {

    private final ObjectMapper objectMapper;

    @Autowired
    public DefaultAgentResultPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DefaultAgentResultPolicy() {
        this(new ObjectMapper());
    }

    @Override
    public Decision evaluate(AgentDefinitionVersion version, ModelProviderResponse response) {
        Decision signal = responseSignal(response);
        if (!signal.accepted()) { return signal; }
        return StringUtils.hasText(response.content()) ? Decision.accept()
                : Decision.rejected(ResultStatus.EMPTY, "AGENT_RESULT_EMPTY");
    }

    /** Check before interpreting any model message, including native tool calls and repair attempts. */
    public Decision responseSignal(ModelProviderResponse response) {
        if (duplicateFields(response.content())) {
            return Decision.rejected(ResultStatus.REJECTED, "AGENT_RESULT_STATUS_INVALID");
        }
        String finishReason = response.finishReason();
        if ("content_filter".equalsIgnoreCase(finishReason)) {
            return Decision.rejected(ResultStatus.REJECTED, "AGENT_RESULT_CONTENT_FILTERED");
        }
        if ("evidence_insufficient".equalsIgnoreCase(finishReason)) {
            return Decision.rejected(ResultStatus.REJECTED, "AGENT_RESULT_EVIDENCE_INSUFFICIENT");
        }
        if ("business_rejected".equalsIgnoreCase(finishReason)
                || "rejected".equalsIgnoreCase(finishReason)) {
            return Decision.rejected(ResultStatus.REJECTED, "AGENT_RESULT_BUSINESS_REJECTED");
        }
        if ("length".equalsIgnoreCase(finishReason)) {
            return Decision.rejected(ResultStatus.PARTIAL, "AGENT_RESULT_INCOMPLETE");
        }
        Decision declared = StringUtils.hasText(response.content()) ? declaredBusinessDecision(response.content()) : null;
        if (declared != null) {
            return declared;
        }
        return Decision.accept();
    }

    /** Ambiguous JSON must not hide a refusal behind a later duplicate field, including during repair. */
    private boolean duplicateFields(String content) {
        if (!StringUtils.hasText(content)) { return false; }
        var objects = new java.util.ArrayDeque<java.util.Set<String>>();
        try (var parser = objectMapper.getFactory().copy()
                .disable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).createParser(content)) {
            com.fasterxml.jackson.core.JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == com.fasterxml.jackson.core.JsonToken.START_OBJECT) { objects.push(new java.util.HashSet<>()); }
                else if (token == com.fasterxml.jackson.core.JsonToken.END_OBJECT) { objects.pop(); }
                else if (token == com.fasterxml.jackson.core.JsonToken.FIELD_NAME && !objects.peek().add(parser.currentName())) { return true; }
            }
        } catch (IOException ignored) {
            // Non-JSON text and ordinary syntax failures retain the existing bounded JSON-repair path.
        }
        return false;
    }

    /**
     * A provider may return a structurally valid result with explicit business
     * semantics.  Honour only the small, documented envelope; arbitrary model
     * text never changes the workflow decision.
     */
    private Decision declaredBusinessDecision(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !root.isObject() || !root.has("resultStatus")) {
                return null;
            }
            return switch (root.path("resultStatus").asText().toUpperCase(java.util.Locale.ROOT)) {
                case "SUCCESS" -> Decision.accept();
                case "EMPTY" -> Decision.rejected(ResultStatus.EMPTY, "AGENT_RESULT_EMPTY");
                case "PARTIAL" -> Decision.rejected(ResultStatus.PARTIAL, "AGENT_RESULT_PARTIAL");
                case "REJECTED" -> Decision.rejected(ResultStatus.REJECTED, "AGENT_RESULT_BUSINESS_REJECTED");
                default -> Decision.rejected(ResultStatus.REJECTED, "AGENT_RESULT_STATUS_INVALID");
            };
        } catch (IOException ignored) {
            return null;
        }
    }
}
