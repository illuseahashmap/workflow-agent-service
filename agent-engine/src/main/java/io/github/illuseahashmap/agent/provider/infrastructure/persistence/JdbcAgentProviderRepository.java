package io.github.illuseahashmap.agent.provider.infrastructure.persistence;

import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderRepository;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
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
public class JdbcAgentProviderRepository implements AgentProviderRepository {

    private static final String SELECT_PROVIDER = """
            SELECT p.*, c.id AS credential_id, c.secret_hint
            FROM agent_provider p
            LEFT JOIN agent_credential c ON c.tenant_code = p.tenant_code AND c.provider_id = p.id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcAgentProviderRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PageSlice<AgentProvider> page(PageCriteria criteria) {
        StringBuilder where = new StringBuilder(" WHERE p.tenant_code = :tenantCode");
        var parameters = new HashMap<String, Object>();
        parameters.put("tenantCode", criteria.tenantCode());
        if (criteria.keyword() != null) {
            where.append(" AND (LOWER(p.provider_code) LIKE :keyword OR LOWER(p.provider_name) LIKE :keyword)");
            parameters.put("keyword", "%" + criteria.keyword().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (criteria.enabled() != null) {
            where.append(" AND p.enabled = :enabled");
            parameters.put("enabled", criteria.enabled() ? 1 : 0);
        }
        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_provider p" + where, parameters, Long.class);
        parameters.put("limit", criteria.pageSize());
        parameters.put("offset", (criteria.pageNum() - 1) * criteria.pageSize());
        List<AgentProvider> items = jdbcTemplate.query(
                SELECT_PROVIDER + where + " ORDER BY p.updated_at DESC, p.id DESC LIMIT :limit OFFSET :offset",
                parameters,
                (resultSet, rowNum) -> mapProvider(resultSet));
        return new PageSlice<>(total, criteria.pageNum(), criteria.pageSize(), items);
    }

    @Override
    public List<AgentProvider> findEnabled(String tenantCode) {
        return jdbcTemplate.query(
                SELECT_PROVIDER + " WHERE p.tenant_code = :tenantCode AND p.enabled = 1 ORDER BY p.provider_name",
                Map.of("tenantCode", tenantCode),
                (resultSet, rowNum) -> mapProvider(resultSet));
    }

    @Override
    public Optional<AgentProvider> findById(String tenantCode, long id) {
        return jdbcTemplate.query(
                SELECT_PROVIDER + " WHERE p.tenant_code = :tenantCode AND p.id = :id",
                Map.of("tenantCode", tenantCode, "id", id),
                (resultSet, rowNum) -> mapProvider(resultSet)).stream().findFirst();
    }

    @Override
    public boolean existsByCode(String tenantCode, String code, Long excludedId) {
        String sql = "SELECT COUNT(*) FROM agent_provider"
                + " WHERE tenant_code = :tenantCode AND provider_code = :code"
                + (excludedId == null ? "" : " AND id <> :excludedId");
        var parameters = new HashMap<String, Object>();
        parameters.put("tenantCode", tenantCode);
        parameters.put("code", code);
        if (excludedId != null) {
            parameters.put("excludedId", excludedId);
        }
        return jdbcTemplate.queryForObject(sql, parameters, Long.class) > 0;
    }

    @Override
    public AgentProvider save(AgentProvider provider) {
        long id = jdbcTemplate.queryForObject("""
                        INSERT INTO agent_provider (
                            tenant_code, provider_code, provider_name, provider_type,
                            base_url, default_model, enabled
                        ) VALUES (
                            :tenantCode, :code, :name, :type, :baseUrl, :defaultModel, :enabled
                        ) RETURNING id
                        """,
                providerParameters(provider), Long.class);
        return findById(provider.tenantCode(), id).orElseThrow();
    }

    @Override
    public void update(AgentProvider provider) {
        jdbcTemplate.update("""
                        UPDATE agent_provider
                        SET provider_name = :name,
                            provider_type = :type,
                            base_url = :baseUrl,
                            default_model = :defaultModel,
                            enabled = :enabled,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_code = :tenantCode AND id = :id
                        """,
                providerParameters(provider));
    }

    @Override
    public void saveCredential(String tenantCode, long providerId, String ciphertext, String hint) {
        jdbcTemplate.update("""
                        INSERT INTO agent_credential (
                            tenant_code, provider_id, secret_ciphertext, secret_hint
                        ) VALUES (
                            :tenantCode, :providerId, :ciphertext, :hint
                        )
                        ON CONFLICT (tenant_code, provider_id) DO UPDATE
                        SET secret_ciphertext = EXCLUDED.secret_ciphertext,
                            secret_hint = EXCLUDED.secret_hint,
                            updated_at = CURRENT_TIMESTAMP
                        """,
                Map.of("tenantCode", tenantCode, "providerId", providerId,
                        "ciphertext", ciphertext, "hint", hint));
    }

    private Map<String, Object> providerParameters(AgentProvider provider) {
        var parameters = new HashMap<String, Object>();
        parameters.put("id", provider.id());
        parameters.put("tenantCode", provider.tenantCode());
        parameters.put("code", provider.code());
        parameters.put("name", provider.name());
        parameters.put("type", provider.type().name());
        parameters.put("baseUrl", provider.baseUrl());
        parameters.put("defaultModel", provider.defaultModel());
        parameters.put("enabled", provider.enabled() ? 1 : 0);
        return parameters;
    }

    private AgentProvider mapProvider(ResultSet resultSet) throws SQLException {
        return new AgentProvider(
                resultSet.getLong("id"),
                resultSet.getString("tenant_code"),
                resultSet.getString("provider_code"),
                resultSet.getString("provider_name"),
                AgentProviderType.valueOf(resultSet.getString("provider_type")),
                resultSet.getString("base_url"),
                resultSet.getString("default_model"),
                resultSet.getInt("enabled") == 1,
                resultSet.getObject("credential_id") != null,
                resultSet.getString("secret_hint"),
                resultSet.getObject("created_at", java.time.OffsetDateTime.class),
                resultSet.getObject("updated_at", java.time.OffsetDateTime.class));
    }
}
