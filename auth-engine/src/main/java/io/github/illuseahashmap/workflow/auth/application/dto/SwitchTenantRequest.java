package io.github.illuseahashmap.workflow.auth.application.dto;

import jakarta.validation.constraints.NotBlank;

public record SwitchTenantRequest(@NotBlank String tenantCode) {
}
