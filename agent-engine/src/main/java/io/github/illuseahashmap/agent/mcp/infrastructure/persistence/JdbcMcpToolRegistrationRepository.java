package io.github.illuseahashmap.agent.mcp.infrastructure.persistence;

import io.github.illuseahashmap.agent.mcp.application.port.McpToolRegistrationPort;
import io.github.illuseahashmap.agent.mcp.domain.McpToolSnapshot;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMcpToolRegistrationRepository implements McpToolRegistrationPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcMcpToolRegistrationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void registerReadOnlyTool(String tenantCode, McpToolSnapshot snapshot) {
        jdbc.update("""
                INSERT INTO agent_tool_definition (tool_code, tool_name, input_schema, read_only, enabled)
                VALUES (:toolCode, :toolName, :inputSchema, TRUE, TRUE)
                ON CONFLICT (tool_code) DO UPDATE
                SET tool_name = EXCLUDED.tool_name, input_schema = EXCLUDED.input_schema,
                    read_only = TRUE, enabled = TRUE
                """, Map.of("toolCode", snapshot.registryToolCode(), "toolName", snapshot.toolName(),
                "inputSchema", snapshot.inputSchema()));
        jdbc.update("""
                INSERT INTO agent_tool_tenant_grant (tenant_code, tool_code, enabled)
                VALUES (:tenantCode, :toolCode, TRUE)
                ON CONFLICT (tenant_code, tool_code) DO UPDATE SET enabled = TRUE
                """, Map.of("tenantCode", tenantCode, "toolCode", snapshot.registryToolCode()));
    }
}
