package io.github.illuseahashmap.workflow.tenant.domain;

import java.time.OffsetDateTime;

public record WorkflowTenant(
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
