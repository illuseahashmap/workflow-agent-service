package io.github.illuseahashmap.agent.runtime.application.port;

public record AgentToolDefinition(
        String toolCode,
        String toolName,
        String inputSchema,
        boolean readOnly,
        String capabilityType,
        String riskLevel,
        boolean sideEffect
) {
    public AgentToolDefinition(String toolCode, String toolName, String inputSchema, boolean readOnly) {
        this(toolCode, toolName, inputSchema, readOnly, "DIRECT", "LOW", !readOnly);
    }

    public AgentToolDefinition {
        capabilityType = capabilityType == null || capabilityType.isBlank() ? "DIRECT" : capabilityType;
        riskLevel = riskLevel == null || riskLevel.isBlank() ? "LOW" : riskLevel;
    }
}
