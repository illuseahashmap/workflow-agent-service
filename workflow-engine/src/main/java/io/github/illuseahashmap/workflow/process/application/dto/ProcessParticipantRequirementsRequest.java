package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record ProcessParticipantRequirementsRequest(
        @NotBlank String processDefinitionKey,
        String processDefinitionId,
        Map<String, Object> variables
) {
}
