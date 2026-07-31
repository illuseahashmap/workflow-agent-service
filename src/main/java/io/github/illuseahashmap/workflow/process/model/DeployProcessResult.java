package io.github.illuseahashmap.workflow.process.model;

public record DeployProcessResult(
        String deploymentId,
        String processDefinitionId,
        String processDefinitionKey,
        String processDefinitionName,
        int version
) {
}
