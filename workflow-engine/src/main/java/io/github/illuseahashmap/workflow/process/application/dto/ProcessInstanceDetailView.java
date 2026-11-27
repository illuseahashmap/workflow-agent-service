package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record ProcessInstanceDetailView(
        ProcessInstanceSummaryView instance,
        List<TaskItem> tasks,
        List<VariableItem> variables
) {

    public record TaskItem(
            String taskId,
            String taskDefinitionKey,
            String taskName,
            String assignee,
            List<String> candidateUsers,
            List<String> candidateGroups,
            String status,
            String deleteReason,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            Long durationInMillis
    ) {
    }

    public record VariableItem(
            String variableId,
            String variableName,
            String variableTypeName,
            Object value,
            String executionId,
            String taskId,
            OffsetDateTime createTime,
            OffsetDateTime lastUpdatedTime,
            String scopeId,
            String subScopeId,
            String scopeType
    ) {
    }
}
