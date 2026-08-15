package io.github.illuseahashmap.agent.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderFailureKind;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Validates the small, portable JSON Schema subset supported by the first runtime. */
@Component
public class AgentOutputSchemaValidator {

    private final ObjectMapper objectMapper;

    public AgentOutputSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validateDefinition(String schema) {
        if (!StringUtils.hasText(schema)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(schema);
            if (root == null || !root.isObject()) {
                throw invalid("Output Schema must be a JSON object");
            }
            validateSchemaNode(root);
        } catch (IOException exception) {
            throw invalid("Output Schema must be valid JSON");
        }
    }

    public void validateOutput(String schema, String content) {
        if (!StringUtils.hasText(schema)) {
            return;
        }
        try {
            JsonNode schemaNode = objectMapper.readTree(schema);
            JsonNode output = objectMapper.readTree(content);
            if (!matches(schemaNode, output)) {
                throw invalidOutput("AGENT_OUTPUT_SCHEMA_INVALID");
            }
        } catch (IOException exception) {
            throw invalidOutput("AGENT_OUTPUT_NOT_JSON");
        }
    }

    public void validateInput(String schema, String content) {
        if (!StringUtils.hasText(schema)) {
            return;
        }
        try {
            JsonNode schemaNode = objectMapper.readTree(schema);
            JsonNode input = objectMapper.readTree(content);
            if (!matches(schemaNode, input)) {
                throw new ModelProviderException(
                        "AGENT_INPUT_SCHEMA_INVALID", ModelProviderFailureKind.PERMANENT,
                        "Agent input did not satisfy the configured input contract");
            }
        } catch (IOException exception) {
            throw new ModelProviderException(
                    "AGENT_INPUT_NOT_JSON", ModelProviderFailureKind.PERMANENT,
                    "Agent input must be valid JSON when an input schema is configured", exception);
        }
    }

    private void validateSchemaNode(JsonNode schema) {
        JsonNode type = schema.get("type");
        if (type != null && !type.isTextual()) {
            throw invalid("Output Schema type must be a string");
        }
        JsonNode required = schema.get("required");
        if (required != null && !required.isArray()) {
            throw invalid("Output Schema required must be an array");
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && !properties.isObject()) {
            throw invalid("Output Schema properties must be an object");
        }
        if (properties != null) {
            properties.fields().forEachRemaining(entry -> validateSchemaNode(entry.getValue()));
        }
        JsonNode items = schema.get("items");
        if (items != null) {
            if (!items.isObject()) {
                throw invalid("Schema items must be an object");
            }
            validateSchemaNode(items);
        }
    }

    private boolean matches(JsonNode schema, JsonNode value) {
        JsonNode type = schema.get("type");
        if (type != null && !matchesType(type.asText(), value)) {
            return false;
        }
        JsonNode required = schema.get("required");
        if (required != null && value.isObject()) {
            for (JsonNode field : required) {
                if (!value.has(field.asText())) {
                    return false;
                }
            }
        }
        JsonNode properties = schema.get("properties");
        if (properties != null && value.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (value.has(field.getKey()) && !matches(field.getValue(), value.get(field.getKey()))) {
                    return false;
                }
            }
        }
        JsonNode items = schema.get("items");
        if (items != null && value.isArray()) {
            for (JsonNode item : value) {
                if (!matches(items, item)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean matchesType(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> throw invalid("Unsupported Output Schema type: " + type);
        };
    }

    private ModelProviderException invalid(String message) {
        return new ModelProviderException("AGENT_OUTPUT_SCHEMA_INVALID", ModelProviderFailureKind.PERMANENT, message);
    }

    private ModelProviderException invalidOutput(String code) {
        return new ModelProviderException(code, ModelProviderFailureKind.PERMANENT,
                "Agent output did not satisfy the configured result policy");
    }
}
