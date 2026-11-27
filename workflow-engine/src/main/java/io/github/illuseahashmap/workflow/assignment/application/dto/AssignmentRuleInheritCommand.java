package io.github.illuseahashmap.workflow.assignment.application.dto;

import jakarta.validation.constraints.NotBlank;

public record AssignmentRuleInheritCommand(@NotBlank String processDefinitionId) {
}
