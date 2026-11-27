package io.github.illuseahashmap.workflow.auth.application.dto;

import java.time.OffsetDateTime;
import java.util.Set;

public record TenantMemberView(
        String userId,
        String username,
        String displayName,
        boolean enabled,
        Set<String> roles,
        Set<String> globalRoles,
        OffsetDateTime joinedAt
) {
}
