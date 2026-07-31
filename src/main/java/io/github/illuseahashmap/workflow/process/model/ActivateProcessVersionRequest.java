package io.github.illuseahashmap.workflow.process.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ActivateProcessVersionRequest(
        @NotBlank String processDefinitionKey,
        @Min(1) int version
) {
}
