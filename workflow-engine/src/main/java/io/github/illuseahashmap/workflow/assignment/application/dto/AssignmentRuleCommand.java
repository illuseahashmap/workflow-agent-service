package io.github.illuseahashmap.workflow.assignment.application.dto;

import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;
import io.github.illuseahashmap.workflow.assignment.domain.EmptyUserStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record AssignmentRuleCommand(
        @NotBlank String processDefinitionId,
        @NotBlank String taskDefinitionKey,
        Integer priority,
        @NotNull AssignmentType assignmentType,
        List<String> assignees,
        List<String> candidateUsers,
        List<String> candidateGroups,
        List<String> countersignUsers,
        EmptyUserStrategy emptyUserStrategy,
        String fallbackAssignee,
        Boolean enabled,
        String description,
        List<@Valid AssignmentConditionCommand> conditions
) {
}
