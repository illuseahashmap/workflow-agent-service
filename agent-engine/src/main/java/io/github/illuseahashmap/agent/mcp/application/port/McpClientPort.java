package io.github.illuseahashmap.agent.mcp.application.port;

import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface McpClientPort {
    Session initialize(McpConnectorVersion connector, Duration timeout);
    List<Tool> listTools(Session session, Duration timeout);
    CallResult callTool(Session session, String toolName, Map<String, Object> arguments, Duration timeout);

    record Session(McpConnectorVersion connector, String sessionId, String protocolVersion,
                   String credentialFingerprint) {
        public Session(McpConnectorVersion connector, String sessionId, String protocolVersion) {
            this(connector, sessionId, protocolVersion, "");
        }
    }
    record Tool(String name, String description, String inputSchema) { }
    record CallResult(String output, boolean isError) { }
}
