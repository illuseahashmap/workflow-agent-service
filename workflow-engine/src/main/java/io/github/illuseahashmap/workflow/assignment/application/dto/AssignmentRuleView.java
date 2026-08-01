package io.github.illuseahashmap.workflow.assignment.application.dto;

import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;
import io.github.illuseahashmap.workflow.assignment.domain.EmptyUserStrategy;
import java.time.OffsetDateTime;
import java.util.List;

public record AssignmentRuleView(
        Long id,
        String tenantId,
        String processDefinitionId,
        String processDefinitionKey,
        int version,
        String taskDefinitionKey,
        int priority,
        AssignmentType assignmentType,
        EmptyUserStrategy emptyUserStrategy,
        boolean enabled,
        String description,
        List<AssignmentConditionView> conditions,
        List<AssignmentTargetView> targets,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public AssignmentRuleView {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
