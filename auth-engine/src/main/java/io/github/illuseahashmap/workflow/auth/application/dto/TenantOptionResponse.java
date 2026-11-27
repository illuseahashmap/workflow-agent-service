package io.github.illuseahashmap.workflow.auth.application.dto;

import java.util.Set;

public record TenantOptionResponse(
        String tenantId,
        String tenantCode,
        String tenantName,
        boolean enabled,
        boolean current,
        Set<String> roles
) {
}
