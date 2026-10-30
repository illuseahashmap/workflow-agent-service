package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;
import io.github.illuseahashmap.knowledge.catalog.domain.IndexVersion;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeIndexPort;
import io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelPort;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeIndexLifecyclePort;
import io.github.illuseahashmap.knowledge.ingestion.domain.Chunk;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Writes a complete candidate keyword index in one local transaction; activation is a separate decision. */
@Component
public class PostgresKnowledgeIndexAdapter implements KnowledgeIndexPort, KnowledgeIndexLifecyclePort {
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PostgresKnowledgeIndexAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public IndexVersion build(DocumentVersion documentVersion, List<Chunk> chunks) {
        return build(documentVersion, chunks, null);
    }

    @Override
    @Transactional
    public IndexVersion build(DocumentVersion documentVersion, List<Chunk> chunks,
                              EmbeddingModelPort.Batch embeddings) {
        if (embeddings != null && embeddings.vectors().size() != chunks.size()) {
            throw new IllegalArgumentException("Embedding count must match chunk count");
        }
        MapSqlParameterSource sourceParameters = new MapSqlParameterSource()
                .addValue("tenantCode", documentVersion.tenantCode())
                .addValue("sourceCode", documentVersion.sourceCode());
        jdbcTemplate.queryForObject("""
                SELECT id FROM knowledge_source
                WHERE tenant_code = :tenantCode AND source_code = :sourceCode
                FOR UPDATE
                """, sourceParameters, (row, rowNumber) -> row.getLong("id"));
        assertCurrentSnapshot(sourceParameters, chunks.stream().map(chunk -> chunk.documentVersion().id())
                .collect(java.util.stream.Collectors.toSet()));

        Integer nextVersion = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1
                FROM knowledge_index_version
                WHERE tenant_code = :tenantCode AND source_code = :sourceCode
                """, sourceParameters, Integer.class);
        int version = nextVersion == null ? 1 : nextVersion;
        MapSqlParameterSource indexParameters = sourceParameters
                .addValue("version", version)
                .addValue("embeddingModel", embeddings == null ? "none" : embeddings.modelVersion())
                .addValue("status", "CANDIDATE");
        Long indexId = jdbcTemplate.queryForObject("""
                INSERT INTO knowledge_index_version
                    (tenant_code, source_code, version, embedding_model, status)
                VALUES (:tenantCode, :sourceCode, :version, :embeddingModel, :status)
                RETURNING id
                """, indexParameters, Long.class);
        if (indexId == null) {
            throw new IllegalStateException("Unable to create knowledge index version");
        }
        for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
            Chunk chunk = chunks.get(ordinal);
            jdbcTemplate.update("""
                    INSERT INTO knowledge_chunk
                        (tenant_code, source_code, document_version_id, index_version_id,
                         chunk_id, ordinal, content, content_fingerprint)
                    VALUES (:tenantCode, :sourceCode, :documentVersionId, :indexVersionId,
                            :chunkId, :ordinal, :content, :contentFingerprint)
                    ON CONFLICT (tenant_code, index_version_id, chunk_id) DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("tenantCode", documentVersion.tenantCode())
                    .addValue("sourceCode", documentVersion.sourceCode())
                    .addValue("documentVersionId", chunk.documentVersion().id())
                    .addValue("indexVersionId", indexId)
                    .addValue("chunkId", chunk.chunkId())
                    .addValue("ordinal", chunk.ordinal())
                    .addValue("content", chunk.text())
                    .addValue("contentFingerprint", chunk.contentFingerprint()));
            if (embeddings != null) {
                jdbcTemplate.update("""
                        INSERT INTO knowledge_chunk_embedding
                            (tenant_code, source_code, index_version_id, chunk_id,
                             model_version, dimension, embedding)
                        VALUES (:tenantCode, :sourceCode, :indexVersionId, :chunkId,
                                :modelVersion, :dimension, CAST(:embedding AS vector))
                        """, new MapSqlParameterSource()
                        .addValue("tenantCode", documentVersion.tenantCode())
                        .addValue("sourceCode", documentVersion.sourceCode())
                        .addValue("indexVersionId", indexId)
                        .addValue("chunkId", chunk.chunkId())
                        .addValue("modelVersion", embeddings.modelVersion())
                        .addValue("dimension", embeddings.dimension())
                        .addValue("embedding", vectorLiteral(embeddings.vectors().get(ordinal))));
            }
        }
        return new IndexVersion(indexId, documentVersion.tenantCode(), documentVersion.sourceCode(), version,
                embeddings == null ? "none" : embeddings.modelVersion(), "CANDIDATE", Instant.now());
    }

    @Override
    @Transactional
    public void activate(IndexVersion indexVersion) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantCode", indexVersion.tenantCode())
                .addValue("sourceCode", indexVersion.sourceCode())
                .addValue("indexId", indexVersion.id());
        jdbcTemplate.queryForObject("""
                SELECT id FROM knowledge_source WHERE tenant_code=:tenantCode AND source_code=:sourceCode
                FOR UPDATE
                """, parameters, Long.class);
        var manifest = jdbcTemplate.queryForList("""
                SELECT DISTINCT document_version_id FROM knowledge_chunk
                WHERE tenant_code=:tenantCode AND index_version_id=:indexId
                """, parameters, Long.class);
        assertCurrentSnapshot(parameters, new java.util.HashSet<>(manifest));
        Integer chunkCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM knowledge_chunk
                WHERE tenant_code = :tenantCode AND index_version_id = :indexId
                """, parameters, Integer.class);
        if (chunkCount == null || chunkCount < 1) {
            throw new IllegalStateException("Cannot activate an empty knowledge index");
        }
        if (!"none".equals(indexVersion.embeddingModel())) {
            Integer missingEmbeddings = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM knowledge_chunk c
                JOIN knowledge_index_version i ON i.id = c.index_version_id
                LEFT JOIN knowledge_chunk_embedding e
                  ON e.tenant_code = c.tenant_code AND e.index_version_id = c.index_version_id
                 AND e.chunk_id = c.chunk_id AND e.model_version = i.embedding_model
                WHERE c.tenant_code = :tenantCode AND c.index_version_id = :indexId
                  AND i.embedding_model <> 'none' AND e.chunk_id IS NULL
                    """, parameters, Integer.class);
            if (missingEmbeddings != null && missingEmbeddings > 0) {
                throw new IllegalStateException("Cannot activate an incomplete vector index");
            }
        }
        jdbcTemplate.update("""
                UPDATE knowledge_index_version
                SET status = 'RETIRED'
                WHERE tenant_code = :tenantCode AND source_code = :sourceCode AND status = 'ACTIVE'
                """, parameters);
        int activated = jdbcTemplate.update("""
                UPDATE knowledge_index_version
                SET status = 'ACTIVE'
                WHERE tenant_code = :tenantCode AND source_code = :sourceCode
                  AND id = :indexId AND status = 'CANDIDATE'
                """, parameters);
        if (activated != 1) {
            throw new IllegalStateException("Knowledge index candidate is not activatable");
        }
    }

    private String vectorLiteral(List<Float> vector) {
        return "[" + vector.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private void assertCurrentSnapshot(MapSqlParameterSource parameters, java.util.Set<Long> actual) {
        var expected = jdbcTemplate.queryForList("""
                SELECT DISTINCT ON (d.external_document_id) d.id FROM knowledge_document_version d
                WHERE d.tenant_code=:tenantCode AND d.source_code=:sourceCode AND d.status IN ('READY','ACTIVE')
                  AND NOT EXISTS (SELECT 1 FROM knowledge_document_version newer
                      WHERE newer.tenant_code=d.tenant_code AND newer.source_code=d.source_code
                        AND newer.external_document_id=d.external_document_id AND newer.version>d.version)
                ORDER BY d.external_document_id,d.version DESC
                """, parameters, Long.class);
        if (!actual.equals(new java.util.HashSet<>(expected))) {
            throw new IllegalStateException("Source snapshot changed or is incomplete; rebuild candidate index");
        }
    }
}
