package io.github.illuseahashmap.workflow.assignment.domain;

import java.time.OffsetDateTime;
import java.util.List;

public record NodeAssignmentRule(
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
        List<AssignmentCondition> conditions,
        List<AssignmentTarget> targets,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public NodeAssignmentRule {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public List<String> targetValues(AssignmentTargetType targetType) {
        return targets.stream()
                .filter(target -> target.targetType() == targetType)
                .sorted(java.util.Comparator.comparingInt(AssignmentTarget::sortOrder))
                .map(AssignmentTarget::targetValue)
                .toList();
    }
}
