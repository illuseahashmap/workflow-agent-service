package io.github.illuseahashmap.agent.runtime.application.dto;

import io.github.illuseahashmap.agent.runtime.domain.StepStatus;
import java.time.OffsetDateTime;

public record AgentRunStepView(
        long id,
        long attemptId,
        int sequenceNo,
        String stepType,
        StepStatus status,
        String errorCode,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
