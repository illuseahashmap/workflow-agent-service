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
            jdbcTemplate.update("""
                    INSERT INTO knowledge_retrieval_trace
                        (trace_id, tenant_code, query_fingerprint, authorized_scopes,
                         status, strategy, evidence_count, created_at)
                    VALUES (:traceId, :tenantCode, :queryFingerprint, CAST(:scopes AS jsonb),
                            :status, :strategy, :evidenceCount, :createdAt)
                    ON CONFLICT (trace_id) DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("traceId", trace.traceId())
                    .addValue("tenantCode", trace.tenantCode())
                    .addValue("queryFingerprint", trace.queryFingerprint())
                    .addValue("scopes", scopes)
                    .addValue("status", trace.status().name())
                    .addValue("strategy", trace.strategy())
                    .addValue("evidenceCount", trace.evidenceCount())
                    .addValue("createdAt", Timestamp.from(trace.createdAt())));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize retrieval trace scopes", exception);
        }
    }
}
