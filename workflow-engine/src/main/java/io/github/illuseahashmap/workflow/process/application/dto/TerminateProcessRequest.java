package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.NotBlank;

public record TerminateProcessRequest(
        @NotBlank String processInstanceId,
        @NotBlank String reason
) {
}
