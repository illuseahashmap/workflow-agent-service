package io.github.illuseahashmap.agent.runtime.application.dto;

import io.github.illuseahashmap.agent.runtime.domain.AttemptStatus;
import java.time.OffsetDateTime;

public record AgentRunAttemptView(
        long id,
        int attemptNo,
        AttemptStatus status,
        String errorCode,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
