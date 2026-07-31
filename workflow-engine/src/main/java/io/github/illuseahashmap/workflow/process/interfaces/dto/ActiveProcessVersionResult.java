package io.github.illuseahashmap.workflow.process.interfaces.dto;

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
