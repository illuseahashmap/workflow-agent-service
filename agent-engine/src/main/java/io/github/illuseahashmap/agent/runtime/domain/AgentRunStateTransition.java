package io.github.illuseahashmap.agent.runtime.domain;

import java.time.Instant;

/**
 * Immutable audit record for one Agent run state change.
 */
public record AgentRunStateTransition(
        String tenantCode,
        long agentRunId,
        Long attemptId,
        AgentRunStatus oldStatus,
        AgentRunStatus newStatus,
        String reasonCode,
        AgentRunOperatorType operatorType,
        String operatorId,
        String traceId,
        Instant createdAt
) {
}
