package io.github.illuseahashmap.agent.runtime.application.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.runtime.application.AgentExecutionException;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailure;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Parses and validates a model proposal before platform policy is applied. */
@Component
public class AgentRouteDecisionParser {

    public static final String JSON_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["schemaVersion","mode","reason","requestedTools","steps"],
             "properties":{
               "schemaVersion":{"type":"string","enum":["1"]},
               "mode":{"type":"string","enum":["DIRECT","RAG","MCP","RAG_MCP","ABSTAIN"]},
               "reason":{"type":"string","minLength":1,"maxLength":500},
               "requestedTools":{"type":"array","uniqueItems":true,"items":{"type":"string"}},
               "steps":{"type":"array","maxItems":5,"items":{"type":"string","minLength":1}}
             }}
            """;

    private final ObjectMapper objectMapper;

    public AgentRouteDecisionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgentRouteDecision parse(String content, Set<String> availableTools) {
        return parse(content, availableTools == null ? Map.of()
                : availableTools.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        tool -> tool, tool -> "DIRECT")));
    }

    public AgentRouteDecision parse(String content, Map<String, String> availableTools) {
        try {
            AgentRouteDecision decision = objectMapper.readValue(content, AgentRouteDecision.class);
            validate(decision, availableTools == null ? Map.of() : availableTools);
            return decision;
        } catch (AgentExecutionException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid("Route decision is not valid structured JSON", exception);
        }
    }

    private void validate(AgentRouteDecision decision, Map<String, String> availableTools) {
        if (!AgentRouteDecision.CURRENT_SCHEMA_VERSION.equals(decision.schemaVersion())
                || decision.mode() == null || !StringUtils.hasText(decision.reason())
                || decision.reason().length() > 500 || decision.steps().size() > 5
                || decision.steps().stream().anyMatch(step -> !StringUtils.hasText(step))) {
            throw invalid("Route decision does not satisfy schema version 1", null);
        }
        Set<String> requested = new LinkedHashSet<>(decision.requestedTools());
        if (requested.size() != decision.requestedTools().size()
                || requested.stream().anyMatch(tool -> !StringUtils.hasText(tool)
                        || !availableTools.containsKey(tool))) {
            throw invalid("Route decision requested an unavailable tool", null);
        }
        boolean hasRag = requested.stream().anyMatch(tool ->
                "RAG".equalsIgnoreCase(availableTools.get(tool)));
        boolean hasMcp = requested.stream().anyMatch(tool ->
                "MCP".equalsIgnoreCase(availableTools.get(tool)));
        boolean valid = switch (decision.mode()) {
            case DIRECT -> !hasRag && !hasMcp;
            case RAG -> hasRag && !hasMcp;
            case MCP -> hasMcp && !hasRag;
            case RAG_MCP -> hasRag && hasMcp;
            case ABSTAIN -> requested.isEmpty();
        };
        if (!valid) {
            throw invalid("Route mode and requested tools are inconsistent", null);
        }
    }

    private AgentExecutionException invalid(String message, Exception cause) {
        AgentFailure failure = new AgentFailure(
                "AGENT_ROUTE_INVALID", AgentFailureCategory.RESULT_POLICY,
                false, ResultStatus.REJECTED, message);
        return cause == null ? new AgentExecutionException(failure)
                : new AgentExecutionException(failure, cause);
    }
}
