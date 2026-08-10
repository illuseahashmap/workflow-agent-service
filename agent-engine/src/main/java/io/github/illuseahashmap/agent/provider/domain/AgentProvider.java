package io.github.illuseahashmap.agent.provider.domain;

import java.time.OffsetDateTime;

public record AgentProvider(
        Long id,
        String tenantCode,
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
