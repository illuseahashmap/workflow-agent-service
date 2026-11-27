package io.github.illuseahashmap.workflow.process.application.dto;

import java.time.OffsetDateTime;

public record ProcessDefinitionDiagramView(
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        int version,
        String deploymentId,
        OffsetDateTime deployTime,
        boolean active,
        String bpmnXml,
        String tenantId
) {
}
