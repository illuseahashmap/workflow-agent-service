package io.github.illuseahashmap.workflow.process.domain;

import java.time.Instant;

/** Immutable evidence for a committed workflow state-changing operation. */
public record WorkflowOperationAudit(
        String eventType,
        String tenantCode,
        String actorType,
        String actorId,
        String actorUsername,
        String processInstanceId,
        String processDefinitionKey,
        String taskId,
        String subject,
        String previousState,
        String nextState,
        String reason,
        String traceId,
        Instant occurredAt
) {
}
