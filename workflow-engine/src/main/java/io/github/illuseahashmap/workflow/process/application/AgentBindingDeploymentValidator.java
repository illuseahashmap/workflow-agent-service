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
            if (!"MODEL_ONLY".equals(version.executionMode())) {
                throw invalid("Agent execution mode is not available for workflow deployment: "
                        + version.executionMode());
            }
            if (binding.processWaitTimeoutSeconds() > version.agentRunTimeoutSeconds()) {
                throw invalid("Process wait timeout cannot exceed the Agent run timeout");
            }
            validateRequiredInputMappings(version.inputSchemaJson(), binding.inputMappingJson());
            validateOutputVariables(binding.outputMappingJson());
        }
    }

    private void validateRequiredInputMappings(String schemaJson, String mappingJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return;
        }
        try {
            JsonNode schema = objectMapper.readTree(schemaJson);
            JsonNode mapping = objectMapper.readTree(mappingJson);
            for (JsonNode required : schema.path("required")) {
                if (!mapping.has(required.asText())) {
                    throw invalid("Agent input mapping is missing required field: " + required.asText());
                }
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("Unable to validate Agent input mapping");
        }
    }

    private void validateOutputVariables(String mappingJson) {
        try {
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
            });
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("Unable to validate Agent output mapping");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
