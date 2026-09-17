package io.github.illuseahashmap.agent.mcp.application.port;

public enum McpFailureKind {
    TIMEOUT,
    UNAVAILABLE,
    RATE_LIMITED,
    AUTHENTICATION,
    PROTOCOL_ERROR,
    TOOL_ERROR
}
