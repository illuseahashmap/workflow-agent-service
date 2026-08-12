package io.github.illuseahashmap.agent.definition.infrastructure.persistence;

import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;
import io.github.illuseahashmap.agent.definition.domain.AgentFailurePolicy;
import io.github.illuseahashmap.agent.definition.domain.AgentVersionStatus;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentDefinitionVersionRepository implements AgentDefinitionVersionRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcAgentDefinitionVersionRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<AgentDefinitionVersion> findByDefinition(String tenantCode, long definitionId) {
        return jdbcTemplate.query("""
                        SELECT * FROM agent_definition_version
                        WHERE tenant_code = :tenantCode AND definition_id = :definitionId
                        ORDER BY version DESC
                        """,
                Map.of("tenantCode", tenantCode, "definitionId", definitionId),
                (resultSet, rowNum) -> mapVersion(resultSet));
    }

    @Override
    public Optional<AgentDefinitionVersion> findById(String tenantCode, long definitionId, long versionId) {
        return jdbcTemplate.query("""
                        SELECT * FROM agent_definition_version
                        WHERE tenant_code = :tenantCode AND definition_id = :definitionId AND id = :versionId
                        """,
                Map.of("tenantCode", tenantCode, "definitionId", definitionId, "versionId", versionId),
                (resultSet, rowNum) -> mapVersion(resultSet)).stream().findFirst();
    }

    @Override
    public Optional<AgentDefinitionVersion> findByVersionId(String tenantCode, long versionId) {
        return jdbcTemplate.query("""
                        SELECT * FROM agent_definition_version
                        WHERE tenant_code = :tenantCode AND id = :versionId
                        """,
                Map.of("tenantCode", tenantCode, "versionId", versionId),
                (resultSet, rowNum) -> mapVersion(resultSet)).stream().findFirst();
    }

    @Override
    public Optional<AgentDefinitionVersion> findPublished(String tenantCode, long definitionId) {
        return jdbcTemplate.query("""
                        SELECT * FROM agent_definition_version
                        WHERE tenant_code = :tenantCode AND definition_id = :definitionId
                          AND status = 'PUBLISHED'
                        ORDER BY version DESC
                        LIMIT 1
                        """,
                Map.of("tenantCode", tenantCode, "definitionId", definitionId),
                (resultSet, rowNum) -> mapVersion(resultSet)).stream().findFirst();
    }

    @Override
    public Optional<AgentDefinitionVersion> findLatest(String tenantCode, long definitionId) {
        return findByDefinition(tenantCode, definitionId).stream().findFirst();
    }

    @Override
    public PageSlice<PublishedVersion> pagePublished(PublishedVersionCriteria criteria) {
        StringBuilder where = new StringBuilder("""
                WHERE version.tenant_code = :tenantCode
                  AND version.status = 'PUBLISHED'
                  AND definition.enabled = 1
                """);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("tenantCode", criteria.tenantCode());
        if (criteria.keyword() != null) {
            where.append("""
                    AND (LOWER(definition.agent_code) LIKE :keyword
                      OR LOWER(definition.agent_name) LIKE :keyword)
                    """);
            parameters.put("keyword", "%" + criteria.keyword().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (criteria.versionId() != null) {
            where.append(" AND version.id = :versionId");
            parameters.put("versionId", criteria.versionId());
        }
        long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM agent_definition_version version
                JOIN agent_definition definition
                  ON definition.id = version.definition_id
                 AND definition.tenant_code = version.tenant_code
                """ + where, parameters, Long.class);
        parameters.put("limit", criteria.pageSize());
        parameters.put("offset", (criteria.pageNum() - 1) * criteria.pageSize());
        List<PublishedVersion> items = jdbcTemplate.query("""
                SELECT version.id, version.definition_id, definition.agent_code, definition.agent_name,
                       version.version, version.execution_mode, version.timeout_seconds,
                       version.input_schema, version.output_schema
                FROM agent_definition_version version
                JOIN agent_definition definition
                  ON definition.id = version.definition_id
                 AND definition.tenant_code = version.tenant_code
                """ + where + """
                ORDER BY definition.agent_name, version.version DESC, version.id DESC
                LIMIT :limit OFFSET :offset
                """, parameters, (rs, rowNum) -> new PublishedVersion(
                rs.getLong("id"), rs.getLong("definition_id"), rs.getString("agent_code"),
                rs.getString("agent_name"), rs.getInt("version"),
                AgentExecutionMode.valueOf(rs.getString("execution_mode")),
                rs.getInt("timeout_seconds"), rs.getString("input_schema"),
                rs.getString("output_schema")));
        return new PageSlice<>(total, criteria.pageNum(), criteria.pageSize(), items);
    }

    @Override
    public AgentDefinitionVersion save(AgentDefinitionVersion version) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO agent_definition_version (
                            tenant_code, definition_id, version, status, execution_mode, provider_id, model_name,
                            system_prompt, timeout_seconds, failure_policy, input_schema, output_schema, created_by
                        ) VALUES (
                            :tenantCode, :definitionId, :version, :status, :executionMode, :providerId, :modelName,
                            :systemPrompt, :timeoutSeconds, :failurePolicy, :inputSchema, :outputSchema, :createdBy
                        ) RETURNING *
                        """,
                versionParameters(version),
                (resultSet, rowNum) -> mapVersion(resultSet));
    }

    @Override
    public void updateDraft(AgentDefinitionVersion version) {
        jdbcTemplate.update("""
                        UPDATE agent_definition_version
                        SET provider_id = :providerId,
                            execution_mode = :executionMode,
                            model_name = :modelName,
                            system_prompt = :systemPrompt,
                            timeout_seconds = :timeoutSeconds,
                            failure_policy = :failurePolicy,
                            input_schema = :inputSchema,
                            output_schema = :outputSchema,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_code = :tenantCode AND definition_id = :definitionId
                          AND id = :id AND status = 'DRAFT'
                        """,
                versionParameters(version));
    }

    @Override
    public void publish(String tenantCode, long definitionId, long versionId, String publishedBy) {
        jdbcTemplate.update("""
                        UPDATE agent_definition_version
                        SET status = 'PUBLISHED',
                            published_by = :publishedBy,
                            published_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_code = :tenantCode AND definition_id = :definitionId
                          AND id = :versionId AND status = 'DRAFT'
                        """,
                Map.of("tenantCode", tenantCode, "definitionId", definitionId,
                        "versionId", versionId, "publishedBy", publishedBy));
    }

    private Map<String, Object> versionParameters(AgentDefinitionVersion version) {
        var parameters = new HashMap<String, Object>();
        parameters.put("id", version.id());
        parameters.put("tenantCode", version.tenantCode());
        parameters.put("definitionId", version.definitionId());
        parameters.put("version", version.version());
        parameters.put("status", version.status().name());
        parameters.put("executionMode", version.executionMode().name());
        parameters.put("providerId", version.providerId());
        parameters.put("modelName", version.modelName());
        parameters.put("systemPrompt", version.systemPrompt());
        parameters.put("timeoutSeconds", version.timeoutSeconds());
        parameters.put("failurePolicy", version.failurePolicy().name());
        parameters.put("inputSchema", version.inputSchema());
        parameters.put("outputSchema", version.outputSchema());
        parameters.put("createdBy", version.createdBy());
        return parameters;
    }

    private AgentDefinitionVersion mapVersion(ResultSet resultSet) throws SQLException {
        Long providerId = resultSet.getObject("provider_id", Long.class);
        return new AgentDefinitionVersion(
                resultSet.getLong("id"),
                resultSet.getString("tenant_code"),
                resultSet.getLong("definition_id"),
                resultSet.getInt("version"),
                AgentVersionStatus.valueOf(resultSet.getString("status")),
                AgentExecutionMode.valueOf(resultSet.getString("execution_mode")),
                providerId,
                resultSet.getString("model_name"),
                resultSet.getString("system_prompt"),
                resultSet.getInt("timeout_seconds"),
                AgentFailurePolicy.valueOf(resultSet.getString("failure_policy")),
                resultSet.getString("input_schema"),
                resultSet.getString("output_schema"),
                resultSet.getString("created_by"),
                resultSet.getString("published_by"),
                resultSet.getObject("published_at", java.time.OffsetDateTime.class),
                resultSet.getObject("created_at", java.time.OffsetDateTime.class),
                resultSet.getObject("updated_at", java.time.OffsetDateTime.class));
    }
}
