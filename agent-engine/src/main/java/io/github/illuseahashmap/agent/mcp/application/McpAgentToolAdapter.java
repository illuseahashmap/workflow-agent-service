package io.github.illuseahashmap.agent.mcp.application;

import io.github.illuseahashmap.agent.mcp.application.port.McpCatalogRepository;
import io.github.illuseahashmap.agent.mcp.application.port.McpClientPort;
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
                    .orElseThrow(() -> new IllegalStateException("Published MCP tool snapshot not found"));
            McpConnectorVersion connector = repository.findConnectorVersionForSnapshot(
                    request.tenantCode(), snapshot.id()).orElseThrow(
                    () -> new IllegalStateException("MCP connector version not found"));
            String toolName = toolCode.substring(toolCode.indexOf(':', 4) + 1);
            McpClientPort.Session session = client.initialize(connector, request.timeout());
            McpClientPort.CallResult result = client.callTool(session, toolName, request.arguments(), request.timeout());
            if (result.isError()) {
                throw new IllegalStateException("MCP tool returned isError");
            }
            return new Result(result.output(), request.idempotencyKey());
        }

    }
}
