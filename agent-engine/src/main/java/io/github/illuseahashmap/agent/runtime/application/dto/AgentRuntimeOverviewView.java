package io.github.illuseahashmap.agent.runtime.application.dto;

import java.time.OffsetDateTime;

/** Tenant-scoped operational snapshot. It deliberately contains no unbounded label dimensions. */
public record AgentRuntimeOverviewView(
        long queued,
        long running,
        long pauseRequested,
        long paused,
        long retryWaiting,
        long reviewRequired,
        long expiredLeases,
        long succeededLast24Hours,
        long failedLast24Hours,
        long timedOutLast24Hours,
        long cancelledLast24Hours,
        OffsetDateTime oldestQueuedAt,
        OffsetDateTime generatedAt
) {
}
