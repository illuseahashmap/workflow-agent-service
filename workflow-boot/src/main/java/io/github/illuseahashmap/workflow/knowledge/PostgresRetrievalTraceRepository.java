package io.github.illuseahashmap.workflow.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.knowledge.retrieval.application.port.RetrievalTraceRepository;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalTrace;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/** Persists bounded retrieval facts; source content and model-private reasoning are never stored. */
@Component
public final class PostgresRetrievalTraceRepository implements RetrievalTraceRepository {
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresRetrievalTraceRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(RetrievalTrace trace) {
        try {
            String scopes = objectMapper.writeValueAsString(trace.authorizedScopes());
            String references = objectMapper.writeValueAsString(trace.evidenceReferences());
            jdbcTemplate.update("""
                    INSERT INTO knowledge_retrieval_trace
                        (trace_id, tenant_code, query_fingerprint, authorized_scopes,
                         status, strategy, evidence_count, created_at, profile_version_id,
                         index_version_id, evidence_references, elapsed_millis, index_bindings)
                    VALUES (:traceId, :tenantCode, :queryFingerprint, CAST(:scopes AS jsonb),
                            :status, :strategy, :evidenceCount, :createdAt, :profileVersionId,
                            :indexVersionId, CAST(:references AS jsonb), :elapsedMillis, CAST(:bindings AS jsonb))
                    ON CONFLICT (trace_id) DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("traceId", trace.traceId())
                    .addValue("tenantCode", trace.tenantCode())
                    .addValue("queryFingerprint", trace.queryFingerprint())
                    .addValue("scopes", scopes)
                    .addValue("references", references)
                    .addValue("status", trace.status().name())
                    .addValue("strategy", trace.strategy())
                    .addValue("evidenceCount", trace.evidenceCount())
                    .addValue("createdAt", Timestamp.from(trace.createdAt()))
                    .addValue("profileVersionId", trace.profileVersionId())
                    .addValue("indexVersionId", trace.indexVersionId())
                    .addValue("bindings", objectMapper.writeValueAsString(trace.indexBindings()))
                    .addValue("elapsedMillis", trace.elapsedMillis()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize retrieval trace scopes", exception);
        }
    }

    @Override
    public java.util.Optional<RetrievalTrace> find(String tenantCode, String traceId) {
        return jdbcTemplate.query("""
                SELECT * FROM knowledge_retrieval_trace WHERE tenant_code=:tenant AND trace_id=:trace
                """, java.util.Map.of("tenant", tenantCode, "trace", traceId), (row, number) -> {
            try {
                return new RetrievalTrace(row.getString("trace_id"), tenantCode, row.getString("query_fingerprint"),
                        objectMapper.readValue(row.getString("authorized_scopes"),
                                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {}),
                        io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalStatus.valueOf(row.getString("status")),
                        row.getString("strategy"), row.getInt("evidence_count"), row.getTimestamp("created_at").toInstant(),
                        row.getObject("profile_version_id", Long.class), row.getObject("index_version_id", Long.class),
                        objectMapper.readValue(row.getString("evidence_references"),
                                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference>>() {}),
                        row.getLong("elapsed_millis"),
                        objectMapper.readValue(row.getString("index_bindings"),
                                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<io.github.illuseahashmap.knowledge.retrieval.domain.SourceIndexBinding>>() {}));
            } catch (JsonProcessingException invalid) {
                throw new IllegalStateException("Invalid retrieval trace provenance", invalid);
            }
        }).stream().findFirst();
    }
}
