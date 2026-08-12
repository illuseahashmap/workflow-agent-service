package io.github.illuseahashmap.workflow.process.application;

import io.github.illuseahashmap.workflow.process.application.dto.AgentCompletionCommand;
import io.github.illuseahashmap.workflow.process.application.port.AgentCompletionRunPort;
import io.github.illuseahashmap.workflow.process.application.port.AgentWorkflowExecutionPort;
import java.util.HashMap;
import org.springframework.stereotype.Service;

/** Workflow application use case for idempotently applying an Agent terminal result. */
@Service
public class AgentCompletionRecoveryService {

    private final AgentCompletionRunPort runPort;
    private final AgentWorkflowExecutionPort executionPort;
    private final AgentOutputMappingResolver outputMappingResolver;

    public AgentCompletionRecoveryService(
            AgentCompletionRunPort runPort,
            AgentWorkflowExecutionPort executionPort,
            AgentOutputMappingResolver outputMappingResolver
    ) {
        this.runPort = runPort;
        this.executionPort = executionPort;
        this.outputMappingResolver = outputMappingResolver;
    }

    public void recover(AgentCompletionCommand command) {
        AgentCompletionRunPort.CompletedAgentRun run = runPort
                .lockCompletedRun(command.tenantCode(), command.runId()).orElse(null);
        if (run == null || run.processInstanceId() == null
                || run.currentAttemptId() == null
                || run.currentAttemptId() != command.attemptId()
                || !same(run.activityActivationId(), command.activityActivationId())) {
            return;
        }
        AgentWorkflowExecutionPort.WaitingExecution execution = executionPort.lockWaitingExecution(
                run.processInstanceId(), run.executionId(), run.activityId()).orElse(null);
        if (execution == null || !same(execution.activityActivationId(), run.activityActivationId())) {
            return;
        }
        var variables = new HashMap<String, Object>();
        variables.put("agentRunId", run.id());
        variables.put("agentRunStatus", run.status());
        if ("SUCCEEDED".equals(run.status())) {
            variables.putAll(outputMappingResolver.resolve(
                    run.outputSnapshotJson(), run.outputMappingJson()));
            executionPort.trigger(run.executionId(), variables);
        } else {
            variables.put("agentRunErrorCode",
                    run.errorCode() == null ? "AGENT_FAILED" : run.errorCode());
            if ("CONTINUE_EMPTY".equals(run.processFailurePolicy())) {
                executionPort.trigger(run.executionId(), variables);
            } else {
                executionPort.setLocalVariables(run.executionId(), variables);
            }
        }
        runPort.markWorkflowHandled(run.tenantCode(), run.id());
    }

    private boolean same(String left, String right) {
        return left != null && left.equals(right);
    }
}
