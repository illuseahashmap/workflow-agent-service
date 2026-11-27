package io.github.illuseahashmap.agent.definition.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AgentDefinitionCommand(
        @NotBlank
        @Pattern(regexp = "^[a-z][a-z0-9_-]{2,63}$")
        String code,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String description,
        boolean enabled
) {
}
