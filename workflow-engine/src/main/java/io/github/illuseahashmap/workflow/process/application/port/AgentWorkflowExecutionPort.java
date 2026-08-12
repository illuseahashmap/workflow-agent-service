package io.github.illuseahashmap.workflow.process.application.port;

import java.util.Map;
import java.util.Optional;

/** Application port for inspecting and resuming a waiting workflow execution. */
public interface AgentWorkflowExecutionPort {

    Optional<WaitingExecution> lockWaitingExecution(
            String processInstanceId, String executionId, String activityId);

    void trigger(String executionId, Map<String, Object> variables);

    void setLocalVariables(String executionId, Map<String, Object> variables);

    record WaitingExecution(String executionId, String activityId, String activityActivationId) {
    }
}
