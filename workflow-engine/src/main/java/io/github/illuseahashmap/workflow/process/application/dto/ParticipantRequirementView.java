package io.github.illuseahashmap.workflow.process.application.dto;

import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;

public record ParticipantRequirementView(
        String activityId,
        String activityName,
        AssignmentType assignmentType,
        boolean multiple,
        boolean required
) {
}
