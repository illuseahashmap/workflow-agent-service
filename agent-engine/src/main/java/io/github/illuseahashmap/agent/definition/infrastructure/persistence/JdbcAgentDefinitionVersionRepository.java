package io.github.illuseahashmap.agent.definition.infrastructure.persistence;

import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.definition.domain.AgentFailurePolicy;
import io.github.illuseahashmap.agent.definition.domain.AgentVersionStatus;
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
    public AgentDefinitionVersion save(AgentDefinitionVersion version) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO agent_definition_version (
                            tenant_code, definition_id, version, status, provider_id, model_name,
                            system_prompt, timeout_seconds, failure_policy, output_schema, created_by
                        ) VALUES (
                            :tenantCode, :definitionId, :version, :status, :providerId, :modelName,
                            :systemPrompt, :timeoutSeconds, :failurePolicy, :outputSchema, :createdBy
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
                            model_name = :modelName,
                            system_prompt = :systemPrompt,
                            timeout_seconds = :timeoutSeconds,
                            failure_policy = :failurePolicy,
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
        parameters.put("providerId", version.providerId());
        parameters.put("modelName", version.modelName());
        parameters.put("systemPrompt", version.systemPrompt());
        parameters.put("timeoutSeconds", version.timeoutSeconds());
        parameters.put("failurePolicy", version.failurePolicy().name());
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
                providerId,
                resultSet.getString("model_name"),
                resultSet.getString("system_prompt"),
                resultSet.getInt("timeout_seconds"),
                AgentFailurePolicy.valueOf(resultSet.getString("failure_policy")),
                resultSet.getString("output_schema"),
                resultSet.getString("created_by"),
                resultSet.getString("published_by"),
                resultSet.getObject("published_at", java.time.OffsetDateTime.class),
                resultSet.getObject("created_at", java.time.OffsetDateTime.class),
                resultSet.getObject("updated_at", java.time.OffsetDateTime.class));
    }
}
