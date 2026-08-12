package io.github.illuseahashmap.workflow.process.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Applies the portable Agent mapping path contract, including array indexes and projections. */
@Component
public class AgentOutputMappingResolver {

    private final ObjectMapper objectMapper;

    public AgentOutputMappingResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> resolve(String snapshotJson, String mappingJson) {
        try {
            JsonNode snapshot = objectMapper.readTree(snapshotJson == null ? "{}" : snapshotJson);
            JsonNode content = snapshot.path("content");
            if (content.isTextual()) {
                content = objectMapper.readTree(content.textValue());
            }
            JsonNode mapping = objectMapper.readTree(mappingJson == null ? "{}" : mappingJson);
            Map<String, Object> variables = new HashMap<>();
            JsonNode mappedContent = content;
            mapping.fields().forEachRemaining(entry -> {
                String variableName = entry.getValue().asText();
                if (!variableName.matches("[A-Za-z][A-Za-z0-9_]{0,127}")
                        || ProcessVariablePolicy.isInternalVariable(variableName)) {
                    throw new AgentCompletionContractException(
                            "Unsafe Agent output variable: " + variableName);
                }
                JsonNode value = resolvePath(mappedContent, entry.getKey());
                if (value == null || value.isMissingNode()) {
                    throw new AgentCompletionContractException(
                            "Agent output mapping path is missing: " + entry.getKey());
                }
                variables.put(variableName, objectMapper.convertValue(value, Object.class));
            });
            return variables;
        } catch (AgentCompletionContractException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AgentCompletionContractException("Unable to apply Agent output mapping", exception);
        }
    }

    private JsonNode resolvePath(JsonNode root, String path) {
        List<JsonNode> values = resolve(List.of(root), path.split("\\."), 0);
        if (values.isEmpty()) {
            return objectMapper.missingNode();
        }
        if (path.contains("*")) {
            var array = objectMapper.createArrayNode();
            values.forEach(array::add);
            return array;
        }
        return values.getFirst();
    }

    private List<JsonNode> resolve(List<JsonNode> current, String[] segments, int index) {
        if (index == segments.length) {
            return current;
        }
        String segment = segments[index];
        List<JsonNode> next = new ArrayList<>();
        for (JsonNode node : current) {
            if ("*".equals(segment)) {
                if (!node.isArray()) {
                    return List.of();
                }
                node.forEach(next::add);
            } else if (node.isArray() && segment.matches("\\d+")) {
                JsonNode value = node.path(Integer.parseInt(segment));
                if (!value.isMissingNode()) {
                    next.add(value);
                }
            } else {
                JsonNode value = node.path(segment);
                if (!value.isMissingNode()) {
                    next.add(value);
                }
            }
        }
        return resolve(next, segments, index + 1);
    }
}
