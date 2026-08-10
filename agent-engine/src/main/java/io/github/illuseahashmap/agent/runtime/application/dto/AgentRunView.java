package io.github.illuseahashmap.agent.runtime.application.dto;

import io.github.illuseahashmap.agent.runtime.domain.AgentRunStatus;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import java.time.OffsetDateTime;

public record AgentRunView(
        long id,
        String agentCode,
        String agentName,
        int agentVersion,
        AgentRunStatus status,
        ResultStatus resultStatus,
        String processInstanceId,
        String activityId,
        String errorCode,
        OffsetDateTime deadlineAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
