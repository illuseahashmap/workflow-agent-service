package io.github.illuseahashmap.workflow.process.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.process.application.dto.InteractionDataFieldView;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Derives a UI-neutral field contract from a frozen Agent schema and BPMN input binding. */
@Component
public class AgentInputRequirementDeriver {

    private final ObjectMapper objectMapper;

    public AgentInputRequirementDeriver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<InteractionDataFieldView> derive(
            String schemaJson,
            String mappingJson,
            Map<String, Object> variables,
            String activityId,
            String activityName
    ) {
        try {
            JsonNode schema = objectMapper.readTree(StringUtils.hasText(schemaJson) ? schemaJson : "{}");
            JsonNode mapping = objectMapper.readTree(StringUtils.hasText(mappingJson) ? mappingJson : "{}");
            if (!mapping.isObject()) {
                throw invalid("Agent input mapping must be a JSON object");
            }
            List<InteractionDataFieldView> fields = new ArrayList<>();
            mapping.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual()) {
                    return;
                }
                rejectUnsupportedInputPath(entry.getKey());
                String variablePath = normalizeVariablePath(entry.getValue().asText());
                JsonNode fieldSchema = resolveSchemaPath(schema, entry.getKey());
                fields.add(new InteractionDataFieldView(
                        variablePath,
                        label(fieldSchema, entry.getKey()),
                        text(fieldSchema, "description"),
                        dataType(fieldSchema),
                        text(fieldSchema, "format"),
                        true,
                        resolveValue(variables, variablePath),
                        activityId,
                        activityName,
                        entry.getKey()
                ));
            });
            return fields;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("Unable to derive Agent interaction fields");
        }
    }

    private String normalizeVariablePath(String expression) {
        String path = expression == null ? "" : expression.trim();
        if (path.startsWith("${") && path.endsWith("}")) {
            path = path.substring(2, path.length() - 1).trim();
        }
        if (!path.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*")) {
            throw invalid("Agent input mapping contains an unsafe process variable path: " + path);
        }
        return path;
    }

    private void rejectUnsupportedInputPath(String path) {
        for (String segment : path.split("\\.")) {
            if (segment.matches("\\d+") || "*".equals(segment)) {
                throw invalid("Agent input mapping does not support array indexes or wildcard paths: " + path);
            }
        }
    }

    private JsonNode resolveSchemaPath(JsonNode schema, String path) {
        JsonNode current = schema;
        for (String segment : path.split("\\.")) {
            if ("array".equals(current.path("type").asText())) {
                current = current.path("items");
                if (segment.matches("\\d+") || "*".equals(segment)) {
                    continue;
                }
            }
            current = current.path("properties").path(segment);
            if (current.isMissingNode()) {
                return objectMapper.missingNode();
            }
        }
        return current;
    }

    private String label(JsonNode schema, String fallback) {
        String title = text(schema, "title");
        return StringUtils.hasText(title) ? title : fallback;
    }

    private String dataType(JsonNode schema) {
        String type = text(schema, "type");
        return StringUtils.hasText(type) ? type : "string";
    }

    private String text(JsonNode schema, String name) {
        JsonNode value = schema.path(name);
        return value.isTextual() ? value.asText() : null;
    }

    private Object resolveValue(Map<String, Object> variables, String path) {
        Object current = variables == null ? null : variables;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> map && map.containsKey(segment)) {
                current = map.get(segment);
            } else if (current instanceof List<?> list && segment.matches("\\d+")) {
                int index = Integer.parseInt(segment);
                current = index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
        }
        return current;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
