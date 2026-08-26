package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;

public record WorkflowOperationAuditView(
        long id,
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
        OffsetDateTime occurredAt
) {
}
