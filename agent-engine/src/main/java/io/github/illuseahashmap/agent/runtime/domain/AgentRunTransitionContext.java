package io.github.illuseahashmap.agent.runtime.domain;

import java.time.Instant;

/**
 * Audit context attached to one state transition.
 */
public record AgentRunTransitionContext(
        Long attemptId,
        String reasonCode,
        AgentRunOperatorType operatorType,
        String operatorId,
        String traceId,
        Instant occurredAt
) {

    public AgentRunTransitionContext {
        if (attemptId != null && attemptId <= 0) {
            throw new IllegalArgumentException("attemptId must be positive when present");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        if (operatorType == null) {
            throw new IllegalArgumentException("operatorType must not be null");
        }
        if (operatorId == null || operatorId.isBlank()) {
            throw new IllegalArgumentException("operatorId must not be blank");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }
}
