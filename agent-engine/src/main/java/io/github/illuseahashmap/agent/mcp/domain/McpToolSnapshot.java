package io.github.illuseahashmap.agent.mcp.domain;

public record McpToolSnapshot(Long id, String tenantCode, long catalogVersionId, String toolName,
                              String description, String inputSchema, String schemaFingerprint,
                              String riskLevel) {

    public String registryToolCode() {
        return "mcp:" + id + ":" + toolName;
    }
}
