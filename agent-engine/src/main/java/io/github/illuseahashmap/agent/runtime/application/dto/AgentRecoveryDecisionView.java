package io.github.illuseahashmap.agent.runtime.application.dto;

import io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory;
import io.github.illuseahashmap.agent.runtime.domain.AgentRecoveryAction;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import java.time.OffsetDateTime;

public record AgentRecoveryDecisionView(
        long id,
        long attemptId,
        long stepId,
        String errorCode,
        AgentFailureCategory failureCategory,
        AgentRecoveryAction action,
        boolean retryScheduled,
        boolean requiresHumanReview,
        ResultStatus resultStatus,
        String reason,
        OffsetDateTime createdAt
) {
}
