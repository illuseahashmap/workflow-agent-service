package io.github.illuseahashmap.workflow.tenant.application.dto;

import java.time.OffsetDateTime;

public record TenantView(
        Long id,
        String tenantId,
        String tenantCode,
        String tenantName,
        String description,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
