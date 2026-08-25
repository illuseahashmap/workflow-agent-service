package io.github.illuseahashmap.agent.mcp.domain;

public record McpConnectorVersion(Long id, String tenantCode, long connectorId, int version,
                                  String endpointUrl, String protocolVersion, String credentialRef,
                                  int timeoutSeconds, String status) {
}
