package io.github.illuseahashmap.workflow.process.model;

import java.time.OffsetDateTime;

public record ActiveProcessVersionResult(
        String tenantId,
        String processDefinitionKey,
        String processDefinitionId,
        int version,
        String activatedBy,
        OffsetDateTime activatedAt
) {
}
