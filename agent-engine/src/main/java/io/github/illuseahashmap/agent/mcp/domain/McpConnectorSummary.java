package io.github.illuseahashmap.agent.mcp.domain;

/** Read model used by the tenant-facing MCP connector catalog. */
public record McpConnectorSummary(
        long connectorId,
        String connectorCode,
        String connectorName,
        String connectorStatus,
        long connectorVersionId,
        int connectorVersion,
        String endpointUrl,
        String protocolVersion,
        String connectorVersionStatus,
        Long latestCatalogVersionId,
        String latestCatalogStatus,
        int toolCount
) {
}
