package io.github.illuseahashmap.agent.mcp.application.dto;

public record McpConnectorSummaryView(
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
