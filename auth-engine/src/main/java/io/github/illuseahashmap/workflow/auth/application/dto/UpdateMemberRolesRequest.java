package io.github.illuseahashmap.workflow.auth.application.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

public record UpdateMemberRolesRequest(@NotEmpty Set<String> roleCodes) {
}
