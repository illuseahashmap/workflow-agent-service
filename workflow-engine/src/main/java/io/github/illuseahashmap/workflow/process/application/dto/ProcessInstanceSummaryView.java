package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;

public record ProcessInstanceSummaryView(
        String processInstanceId,
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        Integer processDefinitionVersion,
        String latestTaskId,
        String businessKey,
        String startUserId,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        OffsetDateTime lastUpdateTime,
        Long durationInMillis,
        String deleteReason,
        String status,
        String tenantId
) {
}
