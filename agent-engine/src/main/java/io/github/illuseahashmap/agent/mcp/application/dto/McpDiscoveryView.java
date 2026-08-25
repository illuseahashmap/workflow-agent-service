package io.github.illuseahashmap.agent.mcp.application.dto;

import java.util.List;

public record McpDiscoveryView(long catalogVersionId, String status, String fingerprint,
                               List<ToolView> tools) {
    public record ToolView(long snapshotId, String registryToolCode, String name,
                           String description, String inputSchema) { }
}
