package io.github.illuseahashmap.agent.mcp.application;

import io.github.illuseahashmap.agent.mcp.application.dto.McpConnectorCommand;
import io.github.illuseahashmap.agent.mcp.application.dto.McpDiscoveryView;
import io.github.illuseahashmap.agent.mcp.application.dto.McpConnectorVersionView;

public interface McpCatalogService {
    McpConnectorVersionView create(McpConnectorCommand command);
    McpDiscoveryView discover(long connectorVersionId);
    void publish(long catalogVersionId);
    void bind(long agentVersionId, long toolSnapshotId);
}
