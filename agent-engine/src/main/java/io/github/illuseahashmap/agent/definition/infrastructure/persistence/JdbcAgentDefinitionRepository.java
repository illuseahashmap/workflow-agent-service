package io.github.illuseahashmap.agent.definition.infrastructure.persistence;

import io.github.illuseahashmap.agent.definition.domain.AgentDefinition;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionRepository;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentDefinitionRepository implements AgentDefinitionRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcAgentDefinitionRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PageSlice<AgentDefinition> page(PageCriteria criteria) {
        StringBuilder where = new StringBuilder(" WHERE tenant_code = :tenantCode");
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("tenantCode", criteria.tenantCode());
        if (criteria.keyword() != null) {
            where.append(" AND (LOWER(agent_code) LIKE :keyword OR LOWER(agent_name) LIKE :keyword)");
            parameters.put("keyword", "%" + criteria.keyword().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (criteria.enabled() != null) {
            where.append(" AND enabled = :enabled");
            parameters.put("enabled", criteria.enabled() ? 1 : 0);
        }
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_definition" + where, parameters, Long.class);
        parameters.put("limit", criteria.pageSize());
        parameters.put("offset", (criteria.pageNum() - 1) * criteria.pageSize());
        List<AgentDefinition> items = jdbcTemplate.query(
                "SELECT * FROM agent_definition" + where
                        + " ORDER BY updated_at DESC, id DESC LIMIT :limit OFFSET :offset",
                parameters,
                (resultSet, rowNum) -> mapDefinition(resultSet));
        return new PageSlice<>(total, criteria.pageNum(), criteria.pageSize(), items);
    }

    @Override
    public Optional<AgentDefinition> findById(String tenantCode, long id) {
        return jdbcTemplate.query(
                        "SELECT * FROM agent_definition WHERE tenant_code = :tenantCode AND id = :id",
                        Map.of("tenantCode", tenantCode, "id", id),
                        (resultSet, rowNum) -> mapDefinition(resultSet))
                .stream()
                .findFirst();
    }

    @Override
    public boolean existsByCode(String tenantCode, String code, Long excludedId) {
        String sql = "SELECT COUNT(*) FROM agent_definition"
                + " WHERE tenant_code = :tenantCode AND agent_code = :code"
                + (excludedId == null ? "" : " AND id <> :excludedId");
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("tenantCode", tenantCode);
        parameters.put("code", code);
        if (excludedId != null) {
            parameters.put("excludedId", excludedId);
        }
        return jdbcTemplate.queryForObject(sql, parameters, Long.class) > 0;
    }

    @Override
    public AgentDefinition save(AgentDefinition definition) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO agent_definition (
                            tenant_code, agent_code, agent_name, description, enabled
                        ) VALUES (
                            :tenantCode, :code, :name, :description, :enabled
                        ) RETURNING *
                        """,
                definitionParameters(definition),
                (resultSet, rowNum) -> mapDefinition(resultSet));
    }

    @Override
    public void update(AgentDefinition definition) {
        jdbcTemplate.update("""
                        UPDATE agent_definition
                        SET agent_name = :name,
                            description = :description,
                            enabled = :enabled,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_code = :tenantCode AND id = :id
                        """,
                definitionParameters(definition));
    }

    private Map<String, Object> definitionParameters(AgentDefinition definition) {
        var parameters = new java.util.HashMap<String, Object>();
        parameters.put("id", definition.id());
        parameters.put("tenantCode", definition.tenantCode());
        parameters.put("code", definition.code());
        parameters.put("name", definition.name());
        parameters.put("description", definition.description());
        parameters.put("enabled", definition.enabled() ? 1 : 0);
        return parameters;
    }

    private AgentDefinition mapDefinition(ResultSet resultSet) throws SQLException {
        return new AgentDefinition(
                resultSet.getLong("id"),
                resultSet.getString("tenant_code"),
                resultSet.getString("agent_code"),
                resultSet.getString("agent_name"),
                resultSet.getString("description"),
                resultSet.getInt("enabled") == 1,
                resultSet.getObject("created_at", java.time.OffsetDateTime.class),
                resultSet.getObject("updated_at", java.time.OffsetDateTime.class));
    }
}
