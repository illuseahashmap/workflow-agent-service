package io.github.illuseahashmap.agent.runtime.domain;

import java.time.Instant;

/**
 * Time-bounded ownership granted to a worker for one attempt.
 */
public record AgentRunLease(long attemptId, String owner, Instant expiresAt) {

    public AgentRunLease {
        if (attemptId <= 0) {
            throw new IllegalArgumentException("attemptId must be positive");
        }
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("lease owner must not be blank");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("lease expiresAt must not be null");
        }
    }
}
