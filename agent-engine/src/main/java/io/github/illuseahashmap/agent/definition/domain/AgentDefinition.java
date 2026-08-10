package io.github.illuseahashmap.agent.definition.domain;

import java.time.OffsetDateTime;

public record AgentDefinition(
        Long id,
        String tenantCode,
        String code,
        String name,
        String description,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
