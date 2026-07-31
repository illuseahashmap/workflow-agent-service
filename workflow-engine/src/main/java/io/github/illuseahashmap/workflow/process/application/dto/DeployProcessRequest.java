package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.NotBlank;

public record DeployProcessRequest(
        @NotBlank String processDefinitionKey,
        @NotBlank String processDefinitionName,
        @NotBlank String bpmnXml
) {
}
