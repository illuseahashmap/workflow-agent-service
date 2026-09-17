package io.github.illuseahashmap.agent.mcp.domain;

public record McpConnector(Long id, String tenantCode, String connectorCode, String connectorName,
                           String status, String createdBy) {
}
