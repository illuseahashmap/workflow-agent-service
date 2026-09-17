package io.github.illuseahashmap.agent.runtime.application.dto;

import io.github.illuseahashmap.agent.runtime.domain.AgentRunOperatorType;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStatus;
import java.time.OffsetDateTime;

public record AgentRunStateHistoryView(
        long id,
        Long attemptId,
        AgentRunStatus oldStatus,
        AgentRunStatus newStatus,
        String reasonCode,
        AgentRunOperatorType operatorType,
        String operatorId,
        String traceId,
        OffsetDateTime createdAt
) {
}
