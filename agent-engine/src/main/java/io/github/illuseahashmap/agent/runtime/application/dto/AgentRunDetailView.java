package io.github.illuseahashmap.agent.runtime.application.dto;

import java.util.List;

public record AgentRunDetailView(
        AgentRunView run,
        AgentRunPayloadView payload,
        List<AgentRunAttemptView> attempts,
        List<AgentRunStepView> steps,
        List<AgentModelInvocationView> modelInvocations,
        List<AgentRunCheckpointView> checkpoints,
        List<AgentRunStateHistoryView> stateHistory,
        List<AgentRecoveryDecisionView> recoveryDecisions
) {
}
