package io.github.illuseahashmap.workflow.auth.application.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Set;

public record SaveTenantRoleRequest(
        @NotBlank String roleCode,
        @NotBlank String roleName,
        String description,
        Boolean enabled,
        Set<String> permissions
) {
}
