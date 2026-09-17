package io.github.illuseahashmap.workflow.process.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Applies the explicit BPMN input contract without leaking unmapped process state. */
@Component
public class AgentInputMappingResolver {

    private final ObjectMapper objectMapper;

    public AgentInputMappingResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String resolve(String mappingJson, Map<String, Object> processVariables) {
        try {
            Map<String, Object> businessVariables = new LinkedHashMap<>();
            if (processVariables != null) {
                processVariables.forEach((name, value) -> {
                    if (!ProcessVariablePolicy.isInternalVariable(name)) {
                        businessVariables.put(name, value);
                    }
                });
            }
            Map<String, Object> mapped = resolveMapping(mappingJson, businessVariables);
            return objectMapper.writeValueAsString(Map.of("input", mapped));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "Unable to serialize Agent input", exception);
        }
    }

    private Map<String, Object> resolveMapping(String mappingJson, Map<String, Object> variables)
            throws JsonProcessingException {
        if (mappingJson == null || mappingJson.isBlank() || "{}".equals(mappingJson.trim())) {
            return Map.of();
        }
        JsonNode mapping = objectMapper.readTree(mappingJson);
        if (!mapping.isObject()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent inputMapping must be a JSON object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        mapping.fields().forEachRemaining(entry ->
                putMappedValue(result, entry.getKey(), resolveMappingValue(entry.getValue(), variables)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private void putMappedValue(Map<String, Object> target, String path, Object value) {
        String[] segments = path.split("\\.");
        Map<String, Object> current = target;
        for (int index = 0; index < segments.length - 1; index++) {
            String segment = segments[index];
            Object existing = current.get(segment);
            if (existing == null) {
                Map<String, Object> nested = new LinkedHashMap<>();
                current.put(segment, nested);
                current = nested;
            } else if (existing instanceof Map<?, ?> map) {
                current = (Map<String, Object>) map;
            } else {
                throw new BusinessException(
                        ErrorCode.BAD_REQUEST, "Agent inputMapping contains conflicting paths: " + path);
            }
        }
        current.put(segments[segments.length - 1], value);
    }

    private Object resolveMappingValue(JsonNode node, Map<String, Object> variables) {
        if (!node.isTextual()) {
            return objectMapper.convertValue(node, Object.class);
        }
        String source = node.textValue().trim();
        if (source.startsWith("${") && source.endsWith("}")) {
            source = source.substring(2, source.length() - 1).trim();
        }
        Object value = resolvePath(variables, source);
        if (value == null && !containsPath(variables, source)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST, "Agent inputMapping references missing process variable: " + source);
        }
        return value;
    }

    private Object resolvePath(Object current, String path) {
        Object value = current;
        for (String segment : path.split("\\.")) {
            if (value instanceof Map<?, ?> map && map.containsKey(segment)) {
                value = map.get(segment);
            } else if (value instanceof List<?> list && segment.matches("\\d+")) {
                int index = Integer.parseInt(segment);
                if (index >= list.size()) {
                    return null;
                }
                value = list.get(index);
            } else {
                return null;
            }
        }
        return value;
    }

    private boolean containsPath(Map<String, Object> variables, String path) {
        String[] segments = path.split("\\.");
        Object current = variables;
        for (String segment : segments) {
            if (current instanceof Map<?, ?> map && map.containsKey(segment)) {
                current = map.get(segment);
            } else if (current instanceof List<?> list && segment.matches("\\d+")) {
                int index = Integer.parseInt(segment);
                if (index >= list.size()) {
                    return false;
                }
                current = list.get(index);
            } else {
                return false;
            }
        }
        return true;
    }
}
