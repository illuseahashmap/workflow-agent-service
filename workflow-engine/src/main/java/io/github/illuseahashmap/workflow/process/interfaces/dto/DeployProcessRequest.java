package io.github.illuseahashmap.workflow.process.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

public record DeployProcessRequest(
        @NotBlank String processDefinitionKey,
        @NotBlank String processDefinitionName,
        @NotBlank String bpmnXml
) {
}
