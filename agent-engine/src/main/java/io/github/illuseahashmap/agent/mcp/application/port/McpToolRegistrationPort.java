package io.github.illuseahashmap.agent.mcp.application.port;

import io.github.illuseahashmap.agent.mcp.domain.McpToolSnapshot;

public interface McpToolRegistrationPort {
    void registerReadOnlyTool(String tenantCode, McpToolSnapshot snapshot);
}
