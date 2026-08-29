package io.github.illuseahashmap.agent.mcp.application.port;

import io.github.illuseahashmap.agent.mcp.domain.McpConnector;
import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import io.github.illuseahashmap.agent.mcp.domain.McpConnectorSummary;
import io.github.illuseahashmap.agent.mcp.domain.McpToolCatalogVersion;
import io.github.illuseahashmap.agent.mcp.domain.McpToolSnapshot;
import java.util.List;
import java.util.Optional;

public interface McpCatalogRepository {
    List<McpConnectorSummary> findConnectorSummaries(String tenantCode);
    McpConnector saveConnector(McpConnector connector);
    McpConnectorVersion saveConnectorVersion(McpConnectorVersion version);
    int deleteDraftConnector(String tenantCode, long connectorId);
    int nextConnectorVersion(String tenantCode, long connectorId);
    Optional<McpConnectorVersion> findConnectorVersion(String tenantCode, long id);
    McpToolCatalogVersion saveCatalog(McpToolCatalogVersion catalog);
    Optional<McpToolCatalogVersion> findCatalog(String tenantCode, long catalogVersionId);
    void saveSnapshots(List<McpToolSnapshot> snapshots);
    List<McpToolSnapshot> findSnapshots(String tenantCode, long catalogVersionId);
    Optional<McpToolSnapshot> findSnapshotByRegistryCode(String tenantCode, String registryToolCode);
    Optional<McpConnectorVersion> findConnectorVersionForSnapshot(String tenantCode, long snapshotId);
    void publishCatalog(String tenantCode, long catalogVersionId, String reviewedBy);
    void bindSnapshotToAgentVersion(String tenantCode, long agentVersionId, long snapshotId);
    void unbindSnapshotFromAgentVersion(String tenantCode, long agentVersionId, long snapshotId);
}
