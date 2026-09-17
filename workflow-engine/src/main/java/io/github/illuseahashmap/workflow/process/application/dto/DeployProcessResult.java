package io.github.illuseahashmap.workflow.process.application.dto;

public record DeployProcessResult(
        String deploymentId,
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        int version
) {
}
