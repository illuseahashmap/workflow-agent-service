package io.github.illuseahashmap.workflow.assignment.application.dto;

import io.github.illuseahashmap.workflow.assignment.domain.AssignmentTargetType;

public record AssignmentTargetView(
        Long id,
        AssignmentTargetType targetType,
        String targetValue,
        int sortOrder
) {
}
