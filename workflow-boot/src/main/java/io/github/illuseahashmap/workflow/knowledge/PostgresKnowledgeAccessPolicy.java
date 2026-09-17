package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeAccessPolicy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Resolves principal grants after the published profile has bounded the candidate scopes. */
@Component
public final class PostgresKnowledgeAccessPolicy implements KnowledgeAccessPolicy {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PostgresKnowledgeAccessPolicy(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<String> authorize(String tenantCode, List<String> requestedScopes) {
        return List.of();
    }

    @Override
    public List<String> authorize(String tenantCode, String principalId,
                                  List<String> profileScopes, List<String> requestedScopes) {
        if (principalId == null || principalId.isBlank()) return List.of();
        List<String> candidates = requestedScopes == null || requestedScopes.isEmpty()
                ? profileScopes : requestedScopes.stream().filter(profileScopes::contains).toList();
        if (candidates.isEmpty()) return List.of();
        return jdbcTemplate.query("""
                SELECT scope_code
                FROM knowledge_principal_scope_grant
                WHERE tenant_code = :tenantCode
                  AND principal_id = :principalId
                  AND scope_code IN (:scopes)
                ORDER BY scope_code
                """, Map.of("tenantCode", tenantCode, "principalId", principalId, "scopes", candidates),
                (rs, rowNum) -> rs.getString("scope_code"));
    }
}
