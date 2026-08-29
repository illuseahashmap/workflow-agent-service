package io.github.illuseahashmap.agent.mcp.application;

import io.github.illuseahashmap.agent.mcp.application.dto.McpConnectorCommand;
import io.github.illuseahashmap.agent.mcp.application.dto.McpDiscoveryView;
import io.github.illuseahashmap.agent.mcp.application.dto.McpConnectorVersionView;
import io.github.illuseahashmap.agent.mcp.application.dto.McpConnectorSummaryView;
import java.util.List;

public interface McpCatalogService {
    List<McpConnectorSummaryView> list(String tenantCode);
    McpConnectorVersionView create(McpConnectorCommand command);
    void deleteDraftConnector(long connectorId);
    McpDiscoveryView discover(long connectorVersionId);
    void publish(long catalogVersionId);
    void bind(long agentVersionId, long toolSnapshotId);
    void unbind(long agentVersionId, long toolSnapshotId);
    List<McpDiscoveryView.ToolView> publishedTools(long catalogVersionId);
}
