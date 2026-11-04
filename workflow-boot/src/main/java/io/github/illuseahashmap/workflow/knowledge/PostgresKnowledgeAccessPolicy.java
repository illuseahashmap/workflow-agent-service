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
        if (principalId == null || principalId.isBlank()) {
            return List.of();
        }
        List<String> candidates = requestedScopes == null || requestedScopes.isEmpty()
                ? profileScopes : requestedScopes.stream().filter(profileScopes::contains).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                SELECT g.scope_code
                FROM knowledge_principal_scope_grant g
                JOIN knowledge_source s ON s.tenant_code=g.tenant_code AND s.source_code=g.scope_code AND s.enabled=TRUE
                WHERE g.tenant_code = :tenantCode
                  AND g.principal_id = :principalId
                  AND g.scope_code IN (:scopes)
                ORDER BY g.scope_code
                """, Map.of("tenantCode", tenantCode, "principalId", principalId, "scopes", candidates),
                (rs, rowNum) -> rs.getString("scope_code"));
    }
}
