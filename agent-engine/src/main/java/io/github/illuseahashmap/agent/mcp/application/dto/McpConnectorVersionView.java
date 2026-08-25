package io.github.illuseahashmap.agent.mcp.application.dto;

public record McpConnectorVersionView(long id, long connectorId, int version, String endpointUrl,
                                      String protocolVersion, String status, int timeoutSeconds) {
}
