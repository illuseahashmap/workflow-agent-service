package io.github.illuseahashmap.workflow.shared.context;

import java.util.Set;

public record CurrentPrincipal(
        String principalType,
        String principalId,
        String username,
        String displayName,
        String tenantCode,
        Set<String> roles,
        Set<String> permissions
) {

    public CurrentPrincipal {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }

    @Override
    public Set<String> roles() {
        return Set.copyOf(roles);
    }

    @Override
    public Set<String> permissions() {
        return Set.copyOf(permissions);
    }
}
