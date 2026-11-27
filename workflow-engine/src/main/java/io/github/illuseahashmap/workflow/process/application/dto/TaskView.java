package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record TaskView(
        String taskId,
        String processInstanceId,
        String processDefinitionId,
        String taskDefinitionKey,
        String taskName,
        String assignee,
        List<String> candidateUsers,
        List<String> candidateGroups,
        String status,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        String deleteReason
) {
}
