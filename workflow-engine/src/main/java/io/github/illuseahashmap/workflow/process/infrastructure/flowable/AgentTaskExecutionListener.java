package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.process.application.port.AgentRunGateway;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;

/** Starts an Agent run when an Agent receive task is entered. The task remains waiting. */
@Component("agentTaskExecutionListener")
public class AgentTaskExecutionListener implements ExecutionListener {

    private static final String WORKFLOW_NAMESPACE = "http://workflow-agent.local/bpmn";

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
        FlowElement current = execution.getCurrentFlowElement();
        ExtensionElement binding = current.getExtensionElements()
                .getOrDefault("agentTask", List.of())
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.WORKFLOW_ERROR,
                        "Agent task is missing workflow:agentTask binding"));
        long agentVersionId = positiveLong(binding.getAttributeValue(null, "agentVersionId"), "agentVersionId");
        String input = serializeVariables(execution.getVariables());
        String tenantCode = tenantProvider.current().tenantCode();
        agentRunGateway.submit(new AgentRunGateway.AgentRunRequest(
                tenantCode,
                agentVersionId,
                execution.getProcessInstanceId(),
                execution.getId(),
                execution.getCurrentActivityId(),
                execution.getId(),
                input,
                "flowable:" + execution.getProcessInstanceId() + ":" + execution.getCurrentActivityId()
                        + ":" + execution.getId(),
                null,
                positiveLongOrDefault(binding.getAttributeValue(null, "timeoutSeconds"), 300)));
    }

    private String serializeVariables(Map<String, Object> variables) {
        try {
            return objectMapper.writeValueAsString(Map.of("input", variables == null ? Map.of() : variables));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "Unable to serialize Agent input", exception);
        }
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
