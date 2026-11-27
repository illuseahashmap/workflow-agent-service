package io.github.illuseahashmap.agent.runtime.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.illuseahashmap.agent.runtime.domain.AgentFailure;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

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
            JsonNode output =
                    objectMapper
                            .reader()
                            .with(
                                    com.fasterxml.jackson.databind.DeserializationFeature
                                            .FAIL_ON_TRAILING_TOKENS)
                            .readTree(content);
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
            JsonNode input =
                    objectMapper
                            .reader()
                            .with(
                                    com.fasterxml.jackson.databind.DeserializationFeature
                                            .FAIL_ON_TRAILING_TOKENS)
                            .readTree(content);
            if (!matches(schemaNode, input)) {
                throw new AgentExecutionException(
                        new AgentFailure(
                                "AGENT_INPUT_SCHEMA_INVALID",
                                AgentFailureCategory.INPUT_CONTRACT,
                                false,
                                ResultStatus.FAILED,
                                "Agent input did not satisfy the configured input contract"));
            }
        } catch (IOException exception) {
            throw new AgentExecutionException(
                    new AgentFailure(
                            "AGENT_INPUT_NOT_JSON",
                            AgentFailureCategory.INPUT_CONTRACT,
                            false,
                            ResultStatus.FAILED,
                            "Agent input must be valid JSON when an input schema is configured"),
                    exception);
        }
    }

    private void validateSchemaNode(JsonNode schema) {
        if (!schema.isObject()) {
            throw invalid("Schema node must be an object");
        }
        JsonNode choices = schema.get("enum");
        if (choices != null && (!choices.isArray() || choices.isEmpty())) {
            throw invalid("Schema enum must be a nonempty array");
        }
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && !additional.isBoolean()) {
            validateSchemaNode(additional);
        }
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
        if (value == null || value.isMissingNode()) {
            return false;
        }
        JsonNode choices = schema.get("enum");
        if (choices != null) {
            boolean found = false;
            for (JsonNode choice : choices) {
                found |= equivalent(choice, value);
            }
            if (!found) {
                return false;
            }
        }
        if (schema.has("const") && !equivalent(schema.get("const"), value)) {
            return false;
        }
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && value.isObject()) {
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!schema.path("properties").has(field.getKey())
                        && (additional.isBoolean()
                                ? !additional.booleanValue()
                                : !matches(additional, field.getValue()))) {
                    return false;
                }
            }
        }
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
                if (value.has(field.getKey())
                        && !matches(field.getValue(), value.get(field.getKey()))) {
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

    private boolean equivalent(JsonNode expected, JsonNode actual) {
        if (expected.isNumber() && actual.isNumber()) {
            return expected.decimalValue().compareTo(actual.decimalValue()) == 0;
        }
        if (expected.isArray() && actual.isArray() && expected.size() == actual.size()) {
            for (int i = 0; i < expected.size(); i++) {
                if (!equivalent(expected.get(i), actual.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isObject() && actual.isObject() && expected.size() == actual.size()) {
            var fields = expected.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!actual.has(field.getKey())
                        || !equivalent(field.getValue(), actual.get(field.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return expected.equals(actual);
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

    private AgentExecutionException invalid(String message) {
        return new AgentExecutionException(
                new AgentFailure(
                        "AGENT_OUTPUT_SCHEMA_INVALID",
                        AgentFailureCategory.OUTPUT_CONTRACT,
                        false,
                        ResultStatus.FAILED,
                        message));
    }

    private AgentExecutionException invalidOutput(String code) {
        return new AgentExecutionException(
                new AgentFailure(
                        code,
                        AgentFailureCategory.OUTPUT_CONTRACT,
                        false,
                        ResultStatus.FAILED,
                        "Agent output did not satisfy the configured result policy"));
    }
}
