package io.github.illuseahashmap.workflow.process.application;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.process.application.dto.AgentCompletionCommand;
import io.github.illuseahashmap.workflow.process.application.port.AgentCompletionRunPort;
import io.github.illuseahashmap.workflow.process.application.port.AgentWorkflowExecutionPort;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentCompletionRecoveryServiceTest {

    private final AgentCompletionRunPort runPort = mock(AgentCompletionRunPort.class);
    private final AgentWorkflowExecutionPort executionPort = mock(AgentWorkflowExecutionPort.class);
    private final AgentCompletionRecoveryService service = new AgentCompletionRecoveryService(
            runPort, executionPort, new AgentOutputMappingResolver(new ObjectMapper()));

    @Test
    void resumesMatchingCurrentAttemptAndMapsOutput() {
        var command = new AgentCompletionCommand("tenant-a", 10, 20, "activation-a", "trace-a");
        when(runPort.lockCompletedRun("tenant-a", 10)).thenReturn(Optional.of(run(20L)));
        when(executionPort.lockWaitingExecution("process-a", "execution-a", "agent-task"))
                .thenReturn(Optional.of(new AgentWorkflowExecutionPort.WaitingExecution(
                        "execution-a", "agent-task", "activation-a")));

        service.recover(command);

        verify(executionPort).trigger("execution-a", Map.of(
                "agentRunId", 10L, "agentRunStatus", "SUCCEEDED", "decision", "APPROVE"));
        verify(runPort).markWorkflowHandled("tenant-a", 10);
    }

    @Test
    void ignoresLateAttemptWithoutTouchingFlowable() {
        var command = new AgentCompletionCommand("tenant-a", 10, 19, "activation-a", "trace-a");
        when(runPort.lockCompletedRun("tenant-a", 10)).thenReturn(Optional.of(run(20L)));

        service.recover(command);

        verify(executionPort, never()).trigger(eq("execution-a"), org.mockito.ArgumentMatchers.anyMap());
        verify(runPort, never()).markWorkflowHandled("tenant-a", 10);
    }

    private AgentCompletionRunPort.CompletedAgentRun run(Long attemptId) {
        return new AgentCompletionRunPort.CompletedAgentRun(
                10, "tenant-a", "process-a", "execution-a", "agent-task", "activation-a",
                attemptId, "SUCCEEDED", null,
                "{\"content\":{\"result\":\"APPROVE\"}}",
                "{\"result\":\"decision\"}", "HOLD_FOR_OPERATIONS");
    }
}
