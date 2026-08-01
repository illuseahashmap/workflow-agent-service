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
}
