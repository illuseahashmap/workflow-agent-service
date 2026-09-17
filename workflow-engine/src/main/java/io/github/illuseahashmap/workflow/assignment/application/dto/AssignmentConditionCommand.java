package io.github.illuseahashmap.workflow.assignment.application.dto;

import io.github.illuseahashmap.rules.RuleConditionOperator;
import jakarta.validation.constraints.NotBlank;

public record AssignmentConditionCommand(
        @NotBlank String variableName,
        RuleConditionOperator operator,
        String variableValue,
        Integer sortOrder
) {
}
