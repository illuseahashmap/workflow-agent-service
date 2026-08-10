package io.github.illuseahashmap.agent.definition.application.dto;

import java.time.OffsetDateTime;

public record AgentDefinitionView(
        long id,
        String code,
        String name,
        String description,
        boolean enabled,
        Integer latestVersion,
        Integer publishedVersion,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
