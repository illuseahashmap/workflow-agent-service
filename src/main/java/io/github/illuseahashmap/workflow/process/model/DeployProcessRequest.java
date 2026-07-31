package io.github.illuseahashmap.workflow.process.model;

import jakarta.validation.constraints.NotBlank;

public record DeployProcessRequest(
        @NotBlank String processDefinitionKey,
        @NotBlank String processDefinitionName,
        @NotBlank String bpmnXml
) {
}
