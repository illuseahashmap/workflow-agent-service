package io.github.illuseahashmap.workflow.assignment.domain;

public record AssignmentTarget(
        Long id,
        AssignmentTargetType targetType,
        String targetValue,
        int sortOrder
) {
}
