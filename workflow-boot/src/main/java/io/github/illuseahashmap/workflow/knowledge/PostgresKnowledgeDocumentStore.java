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
              AND d.content_hash = :documentHash
              AND d.status IN ('READY', 'ACTIVE')
            ORDER BY d.version DESC
            LIMIT 1
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PostgresKnowledgeDocumentStore(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public DocumentSnapshot loadSnapshot(String tenantCode, String sourceCode, String documentHash) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantCode", tenantCode)
                .addValue("sourceCode", sourceCode)
                .addValue("documentHash", documentHash);
        return jdbcTemplate.queryForObject(LOAD_SQL, parameters, (row, rowNumber) -> {
            DocumentVersion version = new DocumentVersion(
                    row.getLong("id"), row.getString("tenant_code"), row.getString("source_code"),
                    row.getString("external_document_id"), row.getInt("version"),
                    row.getString("content_hash"), row.getString("status"),
                    instant(row.getTimestamp("created_at")));
            return new DocumentSnapshot(version, row.getString("content"));
        });
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }
}
