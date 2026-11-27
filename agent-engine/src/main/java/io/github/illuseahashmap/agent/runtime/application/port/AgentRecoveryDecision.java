package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory;
import io.github.illuseahashmap.agent.runtime.domain.AgentRecoveryAction;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import java.time.Instant;

public record AgentRecoveryDecision(
        String tenantCode,
        long agentRunId,
        long attemptId,
        long stepId,
        String errorCode,
        AgentFailureCategory failureCategory,
        AgentRecoveryAction action,
        boolean retryScheduled,
        boolean requiresHumanReview,
        ResultStatus resultStatus,
        String reason,
        Instant createdAt
) {
}
