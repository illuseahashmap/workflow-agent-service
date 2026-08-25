package io.github.illuseahashmap.workflow.agent;

import io.github.illuseahashmap.agent.runtime.application.AgentRunSubmissionService;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentFlowableRunCommand;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunSubmissionView;
import io.github.illuseahashmap.workflow.process.application.dto.AgentRunSubmissionResult;
import io.github.illuseahashmap.workflow.process.application.port.AgentRunGateway;
import org.springframework.stereotype.Component;

/** Composition-root adapter; workflow-engine remains independent of agent-engine. */
@Component
public class AgentRunGatewayAdapter implements AgentRunGateway {

    private final AgentRunSubmissionService submissionService;

    public AgentRunGatewayAdapter(AgentRunSubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @Override
    public AgentRunSubmissionResult submit(AgentRunRequest request) {
        AgentRunSubmissionView result = submissionService.submitFlowable(new AgentFlowableRunCommand(
                request.agentVersionId(),
                request.processInstanceId(),
                request.executionId(),
                request.activityId(),
                request.activityActivationId(),
                request.inputSnapshotJson(),
                request.outputMappingJson(),
                request.processFailurePolicy(),
                request.nodeToolSetJson(),
                request.idempotencyKey(),
                request.requestedBy(),
                request.timeoutSeconds()));
        return new AgentRunSubmissionResult(result.runId(), result.status().name());
    }
}
