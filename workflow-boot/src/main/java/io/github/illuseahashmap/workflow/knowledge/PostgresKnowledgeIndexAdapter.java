package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;
import io.github.illuseahashmap.knowledge.catalog.domain.IndexVersion;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeIndexPort;
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
        MapSqlParameterSource sourceParameters = new MapSqlParameterSource()
                .addValue("tenantCode", documentVersion.tenantCode())
                .addValue("sourceCode", documentVersion.sourceCode());
        jdbcTemplate.queryForObject("""
                SELECT id FROM knowledge_source
                WHERE tenant_code = :tenantCode AND source_code = :sourceCode
                FOR UPDATE
                """, sourceParameters, (row, rowNumber) -> row.getLong("id"));

        Integer nextVersion = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1
                FROM knowledge_index_version
                WHERE tenant_code = :tenantCode AND source_code = :sourceCode
                """, sourceParameters, Integer.class);
        int version = nextVersion == null ? 1 : nextVersion;
        MapSqlParameterSource indexParameters = sourceParameters
                .addValue("version", version)
                .addValue("embeddingModel", "none")
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
        for (Chunk chunk : chunks) {
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
                    .addValue("documentVersionId", documentVersion.id())
                    .addValue("indexVersionId", indexId)
                    .addValue("chunkId", chunk.chunkId())
                    .addValue("ordinal", chunk.ordinal())
                    .addValue("content", chunk.text())
                    .addValue("contentFingerprint", chunk.contentFingerprint()));
        }
        return new IndexVersion(indexId, documentVersion.tenantCode(), documentVersion.sourceCode(), version,
                "none", "CANDIDATE", Instant.now());
    }

    @Override
    @Transactional
    public void activate(IndexVersion indexVersion) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantCode", indexVersion.tenantCode())
                .addValue("sourceCode", indexVersion.sourceCode())
                .addValue("indexId", indexVersion.id());
        Integer chunkCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM knowledge_chunk
                WHERE tenant_code = :tenantCode AND index_version_id = :indexId
                """, parameters, Integer.class);
        if (chunkCount == null || chunkCount < 1) {
            throw new IllegalStateException("Cannot activate an empty knowledge index");
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
}
