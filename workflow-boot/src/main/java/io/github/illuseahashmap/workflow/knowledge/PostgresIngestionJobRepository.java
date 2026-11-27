package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.ingestion.application.port.IngestionJobRepository;
import io.github.illuseahashmap.knowledge.ingestion.domain.IngestionJob;
import io.github.illuseahashmap.knowledge.ingestion.domain.IngestionStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Atomic PostgreSQL lease acquisition for ingestion jobs. */
@Component
public class PostgresIngestionJobRepository implements IngestionJobRepository {
    private static final String CLAIM_SQL = """
            WITH candidate AS (
                SELECT id
                FROM knowledge_ingestion_job
                WHERE tenant_code = :tenantCode
                  AND status IN ('QUEUED', 'RUNNING')
                  AND available_at <= :now
                  AND (lease_expires_at IS NULL OR lease_expires_at <= :now)
                ORDER BY available_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE knowledge_ingestion_job j
            SET status = 'RUNNING', attempt = j.attempt + 1, claim_owner = :owner,
                lease_expires_at = :leaseUntil, updated_at = CURRENT_TIMESTAMP
            FROM candidate c
            WHERE j.id = c.id
            RETURNING j.id, j.tenant_code, j.source_code, j.document_hash, j.status,
                      j.attempt, j.available_at, j.lease_expires_at, j.error_code, j.document_version_id, j.claim_owner
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PostgresKnowledgeIndexAdapter indexes;

    public PostgresIngestionJobRepository(NamedParameterJdbcTemplate jdbcTemplate, PostgresKnowledgeIndexAdapter indexes) {
        this.jdbcTemplate = jdbcTemplate;
        this.indexes = indexes;
    }

    @Override
    public Optional<IngestionJob> claimNext(String tenantCode, Instant now, Instant leaseUntil) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantCode", tenantCode)
                .addValue("owner", java.util.UUID.randomUUID().toString())
                .addValue("now", Timestamp.from(now))
                .addValue("leaseUntil", Timestamp.from(leaseUntil));
        return jdbcTemplate.query(CLAIM_SQL, parameters, (row, rowNumber) -> new IngestionJob(
                row.getLong("id"), row.getString("tenant_code"), row.getString("source_code"),
                row.getString("document_hash"), IngestionStatus.valueOf(row.getString("status")),
                row.getInt("attempt"), instant(row.getTimestamp("available_at")),
                instantOrNull(row.getTimestamp("lease_expires_at")), row.getString("error_code"),
                row.getLong("document_version_id"), row.getString("claim_owner")))
                .stream().findFirst();
    }

    @Override
    public boolean save(IngestionJob job) {
        return jdbcTemplate.update("""
                UPDATE knowledge_ingestion_job
                SET status = :status, attempt = :attempt, available_at = :availableAt,
                    lease_expires_at = :leaseExpiresAt, error_code = :errorCode,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND tenant_code = :tenantCode AND status='RUNNING'
                  AND attempt=:attempt AND claim_owner=:owner AND lease_expires_at > clock_timestamp()
                """, new MapSqlParameterSource()
                .addValue("id", job.id())
                .addValue("tenantCode", job.tenantCode())
                .addValue("status", job.status().name())
                .addValue("attempt", job.attempt())
                .addValue("owner", job.claimOwner())
                .addValue("availableAt", Timestamp.from(job.availableAt()))
                .addValue("leaseExpiresAt", job.leaseExpiresAt() == null
                        ? null : Timestamp.from(job.leaseExpiresAt()))
                .addValue("errorCode", job.errorCode())) == 1;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public boolean complete(IngestionJob job, io.github.illuseahashmap.knowledge.catalog.domain.IndexVersion index) {
        var parameters = ownership(job);
        var locked = jdbcTemplate.queryForList("""
                SELECT id FROM knowledge_ingestion_job WHERE id=:id AND tenant_code=:tenantCode
                AND status='RUNNING' AND attempt=:attempt AND claim_owner=:owner
                AND lease_expires_at > clock_timestamp() FOR UPDATE
                """, parameters);
        if (locked.isEmpty()) {
            return false;
        }
        if (!job.tenantCode().equals(index.tenantCode()) || !job.sourceCode().equals(index.sourceCode())) {
            throw new IllegalArgumentException("Index does not belong to claimed job");
        }
        Integer invalid = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM knowledge_chunk WHERE tenant_code=:tenantCode AND index_version_id=:index
                AND document_version_id=:document
                """, parameters.addValue("index", index.id()).addValue("document", job.documentVersionId()), Integer.class);
        if (invalid == null || invalid < 1) {
            throw new IllegalArgumentException("Index document differs from claimed document version");
        }
        indexes.activate(index);
        if (!save(job.succeeded())) {
            throw new IllegalStateException("Ingestion lease expired during completion");
        }
        return true;
    }

    @Override
    public boolean renew(IngestionJob job, Instant leaseUntil) {
        return jdbcTemplate.update("""
                UPDATE knowledge_ingestion_job SET lease_expires_at=:until
                WHERE id=:id AND tenant_code=:tenantCode AND status='RUNNING'
                AND attempt=:attempt AND claim_owner=:owner AND lease_expires_at > clock_timestamp()
                """, ownership(job).addValue("until", Timestamp.from(leaseUntil))) == 1;
    }

    private MapSqlParameterSource ownership(IngestionJob job) {
        return new MapSqlParameterSource().addValue("id", job.id()).addValue("tenantCode", job.tenantCode())
                .addValue("attempt", job.attempt()).addValue("owner", job.claimOwner());
    }

    private Instant instant(Timestamp value) {
        return value == null ? Instant.EPOCH : value.toInstant();
    }

    private Instant instantOrNull(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
