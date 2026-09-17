package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.port.AgentRunGateway;
import io.github.illuseahashmap.workflow.process.application.AgentInputMappingResolver;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
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
    private final AgentInputMappingResolver inputMappingResolver;

    public AgentTaskExecutionListener(
            AgentRunGateway agentRunGateway,
            TenantProvider tenantProvider,
            AgentInputMappingResolver inputMappingResolver
    ) {
        this.agentRunGateway = agentRunGateway;
        this.tenantProvider = tenantProvider;
        this.inputMappingResolver = inputMappingResolver;
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
        String input = inputMappingResolver.resolve(
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
                binding.getAttributeValue(null, "toolSet"),
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
