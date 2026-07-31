package io.github.illuseahashmap.workflow.process.interfaces.dto;

public record DeployProcessResult(
        String deploymentId,
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        int version
) {
}
