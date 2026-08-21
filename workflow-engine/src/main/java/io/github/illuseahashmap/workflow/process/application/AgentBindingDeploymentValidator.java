package io.github.illuseahashmap.workflow.process.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.process.application.port.AgentVersionCatalog;
import io.github.illuseahashmap.workflow.process.domain.AgentTaskBinding;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Resolves and validates every Agent binding before Flowable accepts a deployment. */
@Service
public class AgentBindingDeploymentValidator {

    private final AgentVersionCatalog versionCatalog;
    private final ObjectMapper objectMapper;

    public AgentBindingDeploymentValidator(AgentVersionCatalog versionCatalog, ObjectMapper objectMapper) {
        this.versionCatalog = versionCatalog;
        this.objectMapper = objectMapper;
    }

    public void validate(String tenantCode, List<AgentTaskBinding> bindings) {
        Set<String> taskKeys = new HashSet<>();
        for (AgentTaskBinding binding : bindings) {
            if (!taskKeys.add(binding.taskDefinitionKey())) {
                throw invalid("Duplicate Agent task binding: " + binding.taskDefinitionKey());
            }
            AgentVersionCatalog.PublishedAgentVersion version = versionCatalog
                    .findPublished(tenantCode, binding.agentVersionId())
                    .orElseThrow(() -> invalid(
                            "Agent version is missing, unpublished, or belongs to another tenant: "
                                    + binding.agentVersionId()));
            if (!Set.of("MODEL_ONLY", "PLATFORM_AGENT").contains(version.executionMode())) {
                throw invalid("Agent execution mode is not available for workflow deployment: "
                        + version.executionMode());
            }
            if (binding.processWaitTimeoutSeconds() > version.agentRunTimeoutSeconds()) {
                throw invalid("Process wait timeout cannot exceed the Agent run timeout");
            }
            if (binding.processFailurePolicy()
                    == io.github.illuseahashmap.workflow.process.domain.AgentProcessFailurePolicy.MANUAL_REVIEW) {
                throw invalid("MANUAL_REVIEW is unavailable until the human review task is implemented");
            }
            validateInputMappings(version.inputSchemaJson(), binding.inputMappingJson());
            validateOutputMappings(version.outputSchemaJson(), binding.outputMappingJson());
        }
    }

    private void validateInputMappings(String schemaJson, String mappingJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return;
        }
        try {
            JsonNode schema = objectMapper.readTree(schemaJson);
            JsonNode mapping = objectMapper.readTree(mappingJson);
            Set<String> mappedFields = new HashSet<>();
            mapping.fieldNames().forEachRemaining(field -> {
                rejectUnsupportedInputPath(field);
                mappedFields.add(field);
                if (resolveSchemaPath(schema, field).isMissingNode()) {
                    throw invalid("Agent input mapping contains an unknown field: " + field);
                }
            });
            for (JsonNode required : schema.path("required")) {
                String requiredField = required.asText();
                if (mappedFields.stream().noneMatch(field ->
                        field.equals(requiredField) || field.startsWith(requiredField + "."))) {
                    throw invalid("Agent input mapping is missing required field: " + required.asText());
                }
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("Unable to validate Agent input mapping");
        }
    }

    private void rejectUnsupportedInputPath(String path) {
        for (String segment : path.split("\\.")) {
            if (segment.matches("\\d+") || "*".equals(segment)) {
                throw invalid("Agent input mapping does not support array indexes or wildcard paths: " + path);
            }
        }
    }

    private void validateOutputMappings(String schemaJson, String mappingJson) {
        try {
            JsonNode schema = schemaJson == null || schemaJson.isBlank()
                    ? null : objectMapper.readTree(schemaJson);
            JsonNode mapping = objectMapper.readTree(mappingJson);
            mapping.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual()) {
                    throw invalid("Agent output mapping values must be process variable names");
                }
                String variable = entry.getValue().asText();
                if (!variable.matches("[A-Za-z][A-Za-z0-9_]{0,127}")
                        || ProcessVariablePolicy.isInternalVariable(variable)) {
                    throw invalid("Agent output mapping contains an unsafe process variable: " + variable);
                }
                if (schema != null && resolveSchemaPath(schema, entry.getKey()).isMissingNode()) {
                    throw invalid("Agent output mapping contains an unknown path: " + entry.getKey());
                }
            });
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("Unable to validate Agent output mapping");
        }
    }

    private JsonNode resolveSchemaPath(JsonNode schema, String path) {
        JsonNode current = schema;
        for (String segment : path.split("\\.")) {
            if ("array".equals(current.path("type").asText())) {
                if (!(segment.matches("\\d+") || "*".equals(segment))) {
                    return objectMapper.missingNode();
                }
                current = current.path("items");
            } else {
                current = current.path("properties").path(segment);
            }
            if (current.isMissingNode()) {
                return current;
            }
        }
        return current;
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
