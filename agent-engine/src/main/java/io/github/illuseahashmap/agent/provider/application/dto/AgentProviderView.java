package io.github.illuseahashmap.agent.provider.application.dto;

import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import java.time.OffsetDateTime;

public record AgentProviderView(
        long id,
        String code,
        String name,
        AgentProviderType type,
        String baseUrl,
        String defaultModel,
        boolean enabled,
        boolean credentialConfigured,
        String credentialHint,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
