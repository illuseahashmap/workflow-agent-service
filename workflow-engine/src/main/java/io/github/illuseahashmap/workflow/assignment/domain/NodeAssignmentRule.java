package io.github.illuseahashmap.workflow.assignment.domain;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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

    public void validate() {
        if (assignmentType == null) {
            throw invalid("Assignment type is required");
        }
        Map<AssignmentTargetType, Long> counts = new EnumMap<>(AssignmentTargetType.class);
        targets.forEach(target -> counts.merge(target.targetType(), 1L, Long::sum));
        boolean targetsMatch = switch (assignmentType) {
            case ASSIGNEE -> counts.getOrDefault(AssignmentTargetType.ASSIGNEE, 0L) == 1;
            case CANDIDATE_USERS -> counts.getOrDefault(AssignmentTargetType.CANDIDATE_USER, 0L) > 0;
            case CANDIDATE_GROUPS -> counts.getOrDefault(AssignmentTargetType.CANDIDATE_GROUP, 0L) > 0;
            case COUNTERSIGN_USERS -> counts.getOrDefault(AssignmentTargetType.COUNTERSIGN_USER, 0L) > 0;
            case MIXED -> counts.getOrDefault(AssignmentTargetType.CANDIDATE_USER, 0L) > 0
                    || counts.getOrDefault(AssignmentTargetType.CANDIDATE_GROUP, 0L) > 0;
        };
        if (!targetsMatch) {
            throw invalid("Assignment targets do not match assignment type " + assignmentType);
        }
        if (emptyUserStrategy == EmptyUserStrategy.TO_ASSIGNEE
                && counts.getOrDefault(AssignmentTargetType.FALLBACK_ASSIGNEE, 0L) != 1) {
            throw invalid("TO_ASSIGNEE requires one fallback assignee");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
