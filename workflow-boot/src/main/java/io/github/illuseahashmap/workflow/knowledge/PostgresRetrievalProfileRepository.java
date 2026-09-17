package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.retrieval.application.port.RetrievalProfileRepository;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalProfileVersion;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import java.sql.Timestamp;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public final class PostgresRetrievalProfileRepository implements RetrievalProfileRepository {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PostgresRetrievalProfileRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<RetrievalProfileVersion> findPublished(String tenantCode, long profileVersionId) {
        return jdbcTemplate.query("""
                SELECT p.id, p.tenant_code, p.profile_code, p.version, p.strategy,
                       p.max_results, p.minimum_score, p.index_version_id, p.status,
                       p.created_at, s.scope_code
                FROM knowledge_retrieval_profile_version p
                LEFT JOIN knowledge_retrieval_profile_scope s
                  ON s.profile_version_id = p.id AND s.tenant_code = p.tenant_code
                WHERE p.tenant_code = :tenantCode AND p.id = :profileVersionId
                  AND p.status = 'PUBLISHED'
                ORDER BY s.scope_code
                """, java.util.Map.of("tenantCode", tenantCode, "profileVersionId", profileVersionId),
                (rs, rowNum) -> new ProfileRow(rs.getLong("id"), rs.getString("tenant_code"),
                        rs.getString("profile_code"), rs.getInt("version"),
                        RetrievalRequest.StrategyHint.valueOf(rs.getString("strategy")),
                        rs.getInt("max_results"), rs.getDouble("minimum_score"),
                        rs.getLong("index_version_id"), rs.getString("status"),
                        instant(rs.getTimestamp("created_at")),
                        rs.getString("scope_code"))).stream().findFirst()
                .map(first -> new RetrievalProfileVersion(first.id, first.tenantCode, first.profileCode,
                        first.version, first.strategy, first.maxResults, first.minimumScore,
                        first.indexVersionId, first.status,
                        jdbcTemplate.query("""
                                SELECT scope_code FROM knowledge_retrieval_profile_scope
                                WHERE tenant_code = :tenantCode AND profile_version_id = :profileVersionId
                                ORDER BY scope_code
                                """, java.util.Map.of("tenantCode", tenantCode, "profileVersionId", profileVersionId),
                                (rs, rowNum) -> rs.getString("scope_code")), first.createdAt));
    }

    private record ProfileRow(long id, String tenantCode, String profileCode, int version,
                              RetrievalRequest.StrategyHint strategy, int maxResults,
                              double minimumScore, long indexVersionId, String status,
                              Instant createdAt, String scopeCode) {}

    private static Instant instant(Timestamp value) {
        return value == null ? Instant.EPOCH : value.toInstant();
    }
}
