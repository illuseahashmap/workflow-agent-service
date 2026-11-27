package io.github.illuseahashmap.agent.runtime.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AgentManualRunCommand(
        @Positive long definitionId,
        @NotBlank @Size(max = 20000) String input
) {
}
