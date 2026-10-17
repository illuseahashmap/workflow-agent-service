package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;
import io.github.illuseahashmap.knowledge.ingestion.domain.Chunk;
import io.github.illuseahashmap.knowledge.retrieval.application.port.ChunkSearchPort;
import io.github.illuseahashmap.knowledge.retrieval.application.port.VectorSearchPort;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Exact pgvector cosine search. Tenant/scope/index filtering happens before ordering and limit. */
@Component
@ConditionalOnProperty(name = "workflow.knowledge.vector.enabled", havingValue = "true")
public final class PostgresVectorSearchAdapter implements VectorSearchPort {
    private final NamedParameterJdbcTemplate jdbc;

    public PostgresVectorSearchAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ChunkSearchPort.SearchHit> search(String tenantCode, RetrievalRequest request,
                                                  List<Float> queryVector, String modelVersion, int limit) {
        if (request.knowledgeScopes().isEmpty() || queryVector.isEmpty()) {
            return List.of();
        }
        if (request.indexVersionId() > 0) {
            String frozenModel = jdbc.queryForObject("""
                    SELECT embedding_model FROM knowledge_index_version
                    WHERE tenant_code = :tenantCode AND id = :indexVersionId
                      AND status IN ('ACTIVE', 'RETIRED')
                    """, new MapSqlParameterSource().addValue("tenantCode", tenantCode)
                    .addValue("indexVersionId", request.indexVersionId()), String.class);
            if (!modelVersion.equals(frozenModel)) {
                throw new IllegalStateException("Published index requires a different embedding model version");
            }
        }
        var params = new MapSqlParameterSource()
                .addValue("tenantCode", tenantCode)
                .addValue("scopes", request.knowledgeScopes())
                .addValue("indexVersionId", request.indexVersionId())
                .addValue("modelVersion", modelVersion)
                .addValue("dimension", queryVector.size())
                .addValue("embedding", "[" + queryVector.stream().map(String::valueOf)
                        .collect(Collectors.joining(",")) + "]")
                .addValue("limit", Math.max(1, Math.min(limit, request.maxResults())));
        return jdbc.query("""
                SELECT c.chunk_id, c.source_code, c.ordinal, c.content, c.content_fingerprint,
                       d.id AS document_version_id, d.external_document_id,
                       d.version AS document_version, d.content_hash,
                       d.status AS document_status, d.created_at AS document_created_at,
                       e.embedding <=> CAST(:embedding AS vector) AS distance
                FROM knowledge_chunk_embedding e
                JOIN knowledge_chunk c ON c.tenant_code = e.tenant_code
                  AND c.index_version_id = e.index_version_id AND c.chunk_id = e.chunk_id
                JOIN knowledge_index_version i ON i.id = e.index_version_id
                  AND i.tenant_code = e.tenant_code AND i.embedding_model = e.model_version
                JOIN knowledge_document_version d ON d.id = c.document_version_id
                  AND d.tenant_code = c.tenant_code
                WHERE e.tenant_code = :tenantCode AND e.source_code IN (:scopes)
                  AND e.model_version = :modelVersion AND e.dimension = :dimension
                  AND ((:indexVersionId = 0 AND i.status = 'ACTIVE')
                    OR (i.id = :indexVersionId AND i.status IN ('ACTIVE', 'RETIRED')))
                  AND d.status IN ('READY', 'ACTIVE')
                ORDER BY distance ASC, c.ordinal ASC LIMIT :limit
                """, params, (row, n) -> {
            var document = new DocumentVersion(row.getLong("document_version_id"), tenantCode,
                    row.getString("source_code"), row.getString("external_document_id"),
                    row.getInt("document_version"), row.getString("content_hash"),
                    row.getString("document_status"), instant(row.getTimestamp("document_created_at")));
            var chunk = new Chunk(row.getString("chunk_id"), document, row.getInt("ordinal"),
                    row.getString("content"), row.getString("content_fingerprint"));
            return new ChunkSearchPort.SearchHit(chunk, Math.max(0, 1.0 - row.getDouble("distance")));
        });
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }
}
