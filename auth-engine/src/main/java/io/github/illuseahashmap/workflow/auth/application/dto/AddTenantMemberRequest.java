package io.github.illuseahashmap.workflow.auth.application.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Set;

public record AddTenantMemberRequest(@NotBlank String username, Set<String> roleCodes) {
}
