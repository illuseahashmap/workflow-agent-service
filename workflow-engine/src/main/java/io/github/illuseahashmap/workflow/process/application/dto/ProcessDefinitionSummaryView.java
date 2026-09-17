package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;

public record ProcessDefinitionSummaryView(
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        int latestVersion,
        String latestDeploymentId,
        OffsetDateTime latestDeployTime,
        Integer activeVersion,
        String activeProcessDefinitionId,
        String activeDeploymentId,
        OffsetDateTime activeUpdateTime,
        String publishStatus,
        String tenantId
) {
}
