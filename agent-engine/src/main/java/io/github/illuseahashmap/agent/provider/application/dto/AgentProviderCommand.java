package io.github.illuseahashmap.agent.provider.application.dto;

import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AgentProviderCommand(
        @NotBlank
        @Pattern(regexp = "^[a-z][a-z0-9_-]{2,63}$")
        String code,
        @NotBlank @Size(max = 128) String name,
        @NotNull AgentProviderType type,
        @Size(max = 512) String baseUrl,
        @Size(max = 128) String defaultModel,
        @Size(max = 4096) String credential,
        boolean enabled
) {
}
