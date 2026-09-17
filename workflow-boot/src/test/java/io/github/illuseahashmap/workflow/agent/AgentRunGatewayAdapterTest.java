package io.github.illuseahashmap.workflow.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.agent.runtime.application.AgentRunSubmissionService;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunSubmissionView;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStatus;
import io.github.illuseahashmap.workflow.process.application.port.AgentRunGateway;
import org.junit.jupiter.api.Test;

class AgentRunGatewayAdapterTest {

    @Test
    void translatesWorkflowRequestWithoutLeakingAgentTypesIntoWorkflowEngine() {
        AgentRunSubmissionService submissionService = mock(AgentRunSubmissionService.class);
        when(submissionService.submitFlowable(any())).thenReturn(
                new AgentRunSubmissionView(42, AgentRunStatus.QUEUED));
        var adapter = new AgentRunGatewayAdapter(submissionService);
        var request = new AgentRunGateway.AgentRunRequest(
                "tenant-a", 7, "process-a", "execution-a", "agent-task", "activation-a",
                "{\"input\":{}}", "{}", "HOLD_FOR_OPERATIONS", "key-a", "user-a", 120);

        var result = adapter.submit(request);

        assertThat(result.runId()).isEqualTo(42);
        assertThat(result.status()).isEqualTo("QUEUED");
        verify(submissionService).submitFlowable(any());
    }
}
