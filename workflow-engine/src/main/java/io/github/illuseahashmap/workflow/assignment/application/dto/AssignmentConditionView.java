package io.github.illuseahashmap.workflow.assignment.application.dto;

import io.github.illuseahashmap.rules.RuleConditionOperator;

public record AssignmentConditionView(
        Long id,
        String variableName,
        RuleConditionOperator operator,
        String variableValue,
        int sortOrder
) {
}
