package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;

public record ProcessDefinitionView(
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        int version,
        String deploymentId,
        OffsetDateTime deployedAt,
        String tenantId,
        boolean active,
        String bpmnXml
) {
}
