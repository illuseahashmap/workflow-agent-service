package io.github.illuseahashmap.agent.runtime.infrastructure.persistence;

import io.github.illuseahashmap.agent.runtime.application.port.AgentToolDefinition;
import io.github.illuseahashmap.agent.runtime.application.port.AgentToolSnapshotRepository;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentToolSnapshotRepository implements AgentToolSnapshotRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAgentToolSnapshotRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void freeze(String tenant, long version, List<AgentToolDefinition> tools) {
        String status = jdbc.queryForObject("""
                SELECT status FROM agent_definition_version WHERE id=:version AND tenant_code=:tenant FOR UPDATE
                """, Map.of("tenant", tenant, "version", version), String.class);
        if ("PUBLISHED".equals(status)) {
            return;
        }
        if (!"DRAFT".equals(status)) {
            throw new io.github.illuseahashmap.workflow.shared.exception.BusinessException(
                    io.github.illuseahashmap.workflow.shared.exception.ErrorCode.CONFLICT,
                    "Only a draft AgentVersion can freeze tools");
        }
        for (var tool : tools) {
            jdbc.update("""
                    INSERT INTO agent_version_tool_snapshot
                    (tenant_code,agent_version_id,tool_code,tool_name,input_schema,read_only,
                     capability_type,risk_level,side_effect,provenance)
                    VALUES(:tenant,:version,:code,:name,CAST(:schema AS jsonb),:readOnly,
                           :capability,:risk,:sideEffect,'PUBLICATION')
                    """, Map.of("tenant", tenant, "version", version, "code", tool.toolCode(),
                    "name", tool.toolName(), "schema", tool.inputSchema(), "readOnly", tool.readOnly(),
                    "capability", tool.capabilityType(), "risk", tool.riskLevel(), "sideEffect", tool.sideEffect()));
        }
    }

    @Override
    public List<AgentToolDefinition> find(String tenant, long version) {
        return jdbc.query("""
                SELECT tool_code,tool_name,input_schema,read_only,capability_type,risk_level,side_effect
                FROM agent_version_tool_snapshot WHERE tenant_code=:tenant AND agent_version_id=:version
                ORDER BY tool_code
                """, Map.of("tenant", tenant, "version", version), (row, n) -> new AgentToolDefinition(
                row.getString("tool_code"), row.getString("tool_name"), row.getString("input_schema"),
                row.getBoolean("read_only"), row.getString("capability_type"), row.getString("risk_level"),
                row.getBoolean("side_effect")));
    }
}
