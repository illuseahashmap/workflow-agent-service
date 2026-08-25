package io.github.illuseahashmap.agent.mcp.application;

import io.github.illuseahashmap.agent.mcp.application.port.McpCatalogRepository;
import io.github.illuseahashmap.agent.mcp.application.port.McpClientPort;
import io.github.illuseahashmap.agent.mcp.application.port.McpClientException;
import io.github.illuseahashmap.agent.mcp.application.port.McpFailureKind;
import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import io.github.illuseahashmap.agent.mcp.domain.McpToolSnapshot;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.agent.runtime.application.port.AgentToolResolver;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Adapts one published read-only MCP snapshot to the existing AgentTool contract. */
@Component
public class McpAgentToolAdapter implements AgentToolResolver {

    private final McpCatalogRepository repository;
    private final McpClientPort client;

    public McpAgentToolAdapter(McpCatalogRepository repository, McpClientPort client) {
        this.repository = repository;
        this.client = client;
    }

    @Override
    public Optional<AgentTool> resolve(String toolCode) {
        if (toolCode == null || !toolCode.startsWith("mcp:")) {
            return Optional.empty();
        }
        return Optional.of(new SnapshotTool(toolCode));
    }

    private final class SnapshotTool implements AgentTool {
        private final String toolCode;

        private SnapshotTool(String toolCode) {
            this.toolCode = toolCode;
        }

        @Override
        public String name() {
            return toolCode;
        }

        @Override
        public String inputSchema() {
            return "{\"type\":\"object\"}";
        }

        @Override
        public Result execute(Request request) {
            McpToolSnapshot snapshot = repository.findSnapshotByRegistryCode(request.tenantCode(), toolCode)
                    .orElseThrow(() -> new McpClientException("MCP_TOOL_NOT_PUBLISHED",
                            McpFailureKind.PROTOCOL_ERROR, false,
                            "Published MCP tool snapshot not found"));
            McpConnectorVersion connector = repository.findConnectorVersionForSnapshot(
                    request.tenantCode(), snapshot.id()).orElseThrow(
                    () -> new McpClientException("MCP_CONNECTOR_NOT_FOUND", McpFailureKind.PROTOCOL_ERROR,
                            false, "MCP connector version not found"));
            String toolName = toolCode.substring(toolCode.indexOf(':', 4) + 1);
            java.time.Instant deadline = java.time.Instant.now().plus(request.timeout());
            McpClientPort.Session session = client.initialize(connector, remaining(deadline));
            McpClientPort.CallResult result = client.callTool(session, toolName, request.arguments(),
                    remaining(deadline));
            if (result.isError()) {
                throw new McpClientException("MCP_TOOL_ERROR", McpFailureKind.TOOL_ERROR, false,
                        "MCP tool returned an error result");
            }
            return new Result(result.output(), request.idempotencyKey());
        }

        private java.time.Duration remaining(java.time.Instant deadline) {
            java.time.Duration remaining = java.time.Duration.between(java.time.Instant.now(), deadline);
            if (remaining.isZero() || remaining.isNegative()) {
                throw new McpClientException("MCP_TIMEOUT", McpFailureKind.TIMEOUT, true,
                        "MCP call exceeded the execution deadline");
            }
            return remaining;
        }

    }
}
