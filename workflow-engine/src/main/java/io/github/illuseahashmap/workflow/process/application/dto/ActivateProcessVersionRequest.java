package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ActivateProcessVersionRequest(
        @NotBlank String processDefinitionKey,
        @Min(1) int version
) {
}
