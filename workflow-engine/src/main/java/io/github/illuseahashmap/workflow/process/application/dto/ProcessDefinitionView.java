package io.github.illuseahashmap.workflow.process.application.dto;

public record ProcessDefinitionView(
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        int version,
        String deploymentId,
        String tenantId,
        boolean active,
        String bpmnXml
) {
}
