package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record TaskParticipantRequirementsRequest(
        @NotBlank String taskId,
        @NotNull TaskParticipantAction action,
        String targetActivityId,
        Map<String, Object> variables
) {
}
