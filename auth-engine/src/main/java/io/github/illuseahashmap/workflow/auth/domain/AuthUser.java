package io.github.illuseahashmap.workflow.auth.domain;

import java.time.OffsetDateTime;

public record AuthUser(
        Long id,
        String userId,
        String username,
        String displayName,
        String passwordHash,
        String tenantCode,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
