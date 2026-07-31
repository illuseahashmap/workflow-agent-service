package io.github.illuseahashmap.workflow.assignment.domain;

import io.github.illuseahashmap.rules.RuleConditionOperator;

public record AssignmentCondition(
        Long id,
        String variableName,
        RuleConditionOperator operator,
        String variableValue,
        int sortOrder
) {
}
