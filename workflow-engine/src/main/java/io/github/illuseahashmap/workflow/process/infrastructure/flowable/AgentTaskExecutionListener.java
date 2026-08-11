package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.process.application.port.AgentRunGateway;
import io.github.illuseahashmap.workflow.process.application.ProcessVariablePolicy;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;

/** Starts an Agent run when an Agent receive task is entered. The task remains waiting. */
@Component("agentTaskExecutionListener")
public class AgentTaskExecutionListener implements ExecutionListener {

    private static final String WORKFLOW_NAMESPACE = "http://workflow-agent.local/bpmn";
    public static final String ACTIVATION_VARIABLE = "__workflowAgentActivationId";

    private final AgentRunGateway agentRunGateway;
    private final TenantProvider tenantProvider;
    private final ObjectMapper objectMapper;

    public AgentTaskExecutionListener(
            AgentRunGateway agentRunGateway,
            TenantProvider tenantProvider,
            ObjectMapper objectMapper
    ) {
        this.agentRunGateway = agentRunGateway;
        this.tenantProvider = tenantProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public void notify(DelegateExecution execution) {
        if (!"start".equals(execution.getEventName())) {
            return;
        }
        start(execution);
    }

    public void start(DelegateExecution execution) {
        FlowElement current = execution.getCurrentFlowElement();
        ExtensionElement binding = current.getExtensionElements().values().stream()
                .flatMap(List::stream)
                .filter(this::isAgentExtension)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_ERROR,
                        "Agent task is missing workflow:agentTask binding"));
        long agentVersionId = positiveLong(binding.getAttributeValue(null, "agentVersionId"), "agentVersionId");
        String input = serializeVariables(
                binding.getAttributeValue(null, "inputMapping"), execution.getVariables());
        String activationId = UUID.randomUUID().toString();
        execution.setVariableLocal(ACTIVATION_VARIABLE, activationId);
        String processFailurePolicy = compatibleProcessFailurePolicy(binding);
        String outputMapping = defaultJson(binding.getAttributeValue(null, "outputMapping"));
        String tenantCode = tenantProvider.current().tenantCode();
        agentRunGateway.submit(new AgentRunGateway.AgentRunRequest(
                tenantCode,
                agentVersionId,
                execution.getProcessInstanceId(),
                execution.getId(),
                execution.getCurrentActivityId(),
                activationId,
                input,
                outputMapping,
                processFailurePolicy,
                "flowable:" + execution.getProcessInstanceId() + ":" + execution.getCurrentActivityId()
                        + ":" + activationId,
                null,
                processWaitTimeout(binding)));
    }

    private String defaultJson(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }

    private String compatibleProcessFailurePolicy(ExtensionElement binding) {
        String value = binding.getAttributeValue(null, "processFailurePolicy");
        if (value == null || value.isBlank()) {
            value = binding.getAttributeValue(null, "failurePolicy");
        }
        if (value == null || value.isBlank() || "FAIL_PROCESS".equals(value)) {
            return "HOLD_FOR_OPERATIONS";
        }
        if (!List.of("CONTINUE_EMPTY", "MANUAL_REVIEW", "HOLD_FOR_OPERATIONS").contains(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported Agent process failure policy");
        }
        return value;
    }

    private long processWaitTimeout(ExtensionElement binding) {
        String value = binding.getAttributeValue(null, "processWaitTimeoutSeconds");
        if (value == null || value.isBlank()) {
            value = binding.getAttributeValue(null, "timeoutSeconds");
        }
        long timeout = positiveLongOrDefault(value, 300);
        if (timeout < 1 || timeout > 3600) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST, "processWaitTimeoutSeconds must be between 1 and 3600");
        }
        return timeout;
    }

    private boolean isAgentExtension(ExtensionElement extension) {
        return "agentTask".equals(extension.getName())
                || "http://workflow-agent.local/bpmn".equals(extension.getNamespace());
    }

    /**
     * Builds the business input contract for the Agent. Flowable contains trusted
     * platform variables as well as user variables; platform variables must never
     * become implicit model context. A non-empty binding is destination -> source.
     */
    private String serializeVariables(String mappingJson, Map<String, Object> variables) {
        try {
            Map<String, Object> businessVariables = new LinkedHashMap<>();
            if (variables != null) {
                variables.forEach((name, value) -> {
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
            return variables;
        }
        JsonNode mapping = objectMapper.readTree(mappingJson);
        if (!mapping.isObject()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent inputMapping must be a JSON object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        mapping.fields().forEachRemaining(entry ->
                result.put(entry.getKey(), resolveMappingValue(entry.getValue(), variables)));
        return result;
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
        if (variables.containsKey(path)) {
            return true;
        }
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

    private long positiveLong(String value, String field) {
        long parsed = positiveLongOrDefault(value, -1);
        if (parsed <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " must be a positive number");
        }
        return parsed;
    }

    private long positiveLongOrDefault(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
