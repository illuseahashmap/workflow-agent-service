package io.github.illuseahashmap.agent.runtime.application.port;

public record AgentToolDefinition(
        String toolCode,
        String toolName,
        String inputSchema,
        boolean readOnly
) {
}
