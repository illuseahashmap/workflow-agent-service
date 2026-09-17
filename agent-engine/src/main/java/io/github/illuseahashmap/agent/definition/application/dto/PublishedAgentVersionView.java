package io.github.illuseahashmap.agent.definition.application.dto;

import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;

/** Compact immutable contract used by workflow authoring clients. */
public record PublishedAgentVersionView(
        long id,
        long definitionId,
        String agentCode,
        String agentName,
        int version,
        AgentExecutionMode executionMode,
        int timeoutSeconds,
        String inputSchema,
        String outputSchema,
        String contractFingerprint
) {
}
