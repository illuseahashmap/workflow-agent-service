package io.github.illuseahashmap.workflow.tenant.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TenantCommand(
        @NotBlank @Size(max = 64) String tenantId,
        @NotBlank @Size(max = 64) String tenantCode,
        @NotBlank @Size(max = 128) String tenantName,
        @Size(max = 512) String description,
        Boolean enabled
) {
}
