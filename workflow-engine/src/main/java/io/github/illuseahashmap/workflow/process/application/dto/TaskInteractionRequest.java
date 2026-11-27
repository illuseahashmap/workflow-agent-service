package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record TaskInteractionRequest(
        @NotBlank String taskId,
        Map<String, Object> variables
) {
}
