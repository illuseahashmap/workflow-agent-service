package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

public record StartProcessRequest(
        @NotBlank String processDefinitionKey,
        String processDefinitionId,
        String businessKey,
        Map<String, Object> variables,
        List<@Valid ParticipantAssignment> participantAssignments
) {
}
