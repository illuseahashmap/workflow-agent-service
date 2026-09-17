package io.github.illuseahashmap.agent.runtime.application.dto;

import java.time.OffsetDateTime;

public record AgentRunCheckpointView(
        long id,
        long attemptId,
        int sequenceNo,
        String checkpointType,
        String snapshotJson,
        OffsetDateTime createdAt
) {
}
