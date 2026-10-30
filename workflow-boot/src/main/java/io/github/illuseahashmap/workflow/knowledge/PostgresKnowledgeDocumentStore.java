package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeDocumentStore;
import io.github.illuseahashmap.knowledge.ingestion.domain.DocumentSnapshot;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/** Reads immutable document content without exposing JDBC to the knowledge domain. */
@Component
public final class PostgresKnowledgeDocumentStore implements KnowledgeDocumentStore {
    private static final String LOAD_SQL = """
            SELECT d.id, d.tenant_code, d.source_code, d.external_document_id, d.version,
                   d.content_hash, d.status, d.created_at, c.content
            FROM knowledge_document_version d
            JOIN knowledge_document_content c ON c.document_version_id = d.id
            WHERE d.tenant_code = :tenantCode
              AND d.source_code = :sourceCode
              AND d.id = :documentVersionId
              AND d.status IN ('READY', 'ACTIVE')
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PostgresKnowledgeDocumentStore(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DocumentSnapshot loadSnapshot(String tenantCode, String sourceCode, long documentVersionId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantCode", tenantCode)
                .addValue("sourceCode", sourceCode)
                .addValue("documentVersionId", documentVersionId);
        return jdbcTemplate.queryForObject(LOAD_SQL, parameters, (row, rowNumber) -> {
            DocumentVersion version = new DocumentVersion(
                    row.getLong("id"), row.getString("tenant_code"), row.getString("source_code"),
                    row.getString("external_document_id"), row.getInt("version"),
                    row.getString("content_hash"), row.getString("status"),
                    instant(row.getTimestamp("created_at")));
            return new DocumentSnapshot(version, row.getString("content"));
        });
    }

    @Override
    public java.util.List<DocumentSnapshot> loadSnapshots(String tenantCode, String sourceCode, long documentVersionId) {
        loadSnapshot(tenantCode, sourceCode, documentVersionId);
        return jdbcTemplate.query("""
                SELECT DISTINCT ON (d.external_document_id) d.id,d.tenant_code,d.source_code,
                    d.external_document_id,d.version,d.content_hash,d.status,d.created_at,c.content
                FROM knowledge_document_version d JOIN knowledge_document_content c ON c.document_version_id=d.id
                WHERE d.tenant_code=:tenant AND d.source_code=:source AND d.status IN ('READY','ACTIVE')
                  AND NOT EXISTS (SELECT 1 FROM knowledge_document_version newer
                      WHERE newer.tenant_code=d.tenant_code AND newer.source_code=d.source_code
                        AND newer.external_document_id=d.external_document_id AND newer.version>d.version)
                ORDER BY d.external_document_id,d.version DESC
                """, new MapSqlParameterSource("tenant", tenantCode).addValue("source", sourceCode),
                (row, n) -> new DocumentSnapshot(new DocumentVersion(row.getLong("id"), tenantCode, sourceCode,
                        row.getString("external_document_id"), row.getInt("version"), row.getString("content_hash"),
                        row.getString("status"), instant(row.getTimestamp("created_at"))), row.getString("content")));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }
}
