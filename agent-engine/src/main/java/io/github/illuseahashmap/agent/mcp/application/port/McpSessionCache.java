package io.github.illuseahashmap.agent.mcp.application.port;

import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import java.util.Optional;

/** Bounded optimization for MCP sessions; the remote protocol remains authoritative. */
public interface McpSessionCache {
    Optional<McpClientPort.Session> find(McpConnectorVersion connector, String credentialFingerprint);
    void save(McpClientPort.Session session);
    void invalidate(McpClientPort.Session session);
    void invalidateConnector(McpConnectorVersion connector);
}
