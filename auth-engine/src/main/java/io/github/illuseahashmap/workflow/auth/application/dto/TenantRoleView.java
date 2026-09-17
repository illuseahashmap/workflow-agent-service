package io.github.illuseahashmap.workflow.auth.application.dto;

import java.util.Set;

public record TenantRoleView(
        String roleCode,
        String roleName,
        String description,
        boolean enabled,
        boolean builtIn,
        Set<String> permissions
) {
}
