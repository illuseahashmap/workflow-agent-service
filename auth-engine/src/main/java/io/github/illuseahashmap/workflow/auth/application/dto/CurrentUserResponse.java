package io.github.illuseahashmap.workflow.auth.application.dto;

import java.util.Set;

public record CurrentUserResponse(
        String userId,
        String username,
        String displayName,
        String tenantCode,
        Set<String> roles,
        Set<String> permissions
) {
}
