package io.github.illuseahashmap.agent.runtime.application.routing;

import java.util.List;

/** Versioned, auditable result of the planning phase. */
public record AgentRouteDecision(
        String schemaVersion,
        AgentRouteMode mode,
        String reason,
        List<String> requestedTools,
        List<String> steps
) {
    public static final String CURRENT_SCHEMA_VERSION = "1";

    public AgentRouteDecision {
        requestedTools = requestedTools == null ? List.of() : List.copyOf(requestedTools);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
