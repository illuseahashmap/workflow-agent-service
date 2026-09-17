package io.github.illuseahashmap.agent.mcp.domain;

public record McpToolCatalogVersion(Long id, String tenantCode, long connectorVersionId,
                                    String status, String contentFingerprint) {
}
