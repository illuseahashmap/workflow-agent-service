package io.github.illuseahashmap.workflow.process.model;

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
