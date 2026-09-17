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
public final class PostgresIngestionJobRepository implements IngestionJobRepository {
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
            SET status = 'RUNNING', attempt = j.attempt + 1,
                lease_expires_at = :leaseUntil, updated_at = CURRENT_TIMESTAMP
            FROM candidate c
            WHERE j.id = c.id
            RETURNING j.id, j.tenant_code, j.source_code, j.document_hash, j.status,
                      j.attempt, j.available_at, j.lease_expires_at, j.error_code
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PostgresIngestionJobRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<IngestionJob> claimNext(String tenantCode, Instant now, Instant leaseUntil) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantCode", tenantCode)
                .addValue("now", Timestamp.from(now))
                .addValue("leaseUntil", Timestamp.from(leaseUntil));
        return jdbcTemplate.query(CLAIM_SQL, parameters, (row, rowNumber) -> new IngestionJob(
                row.getLong("id"), row.getString("tenant_code"), row.getString("source_code"),
                row.getString("document_hash"), IngestionStatus.valueOf(row.getString("status")),
                row.getInt("attempt"), instant(row.getTimestamp("available_at")),
                instantOrNull(row.getTimestamp("lease_expires_at")), row.getString("error_code")))
                .stream().findFirst();
    }

    @Override
    public void save(IngestionJob job) {
        jdbcTemplate.update("""
                UPDATE knowledge_ingestion_job
                SET status = :status, attempt = :attempt, available_at = :availableAt,
                    lease_expires_at = :leaseExpiresAt, error_code = :errorCode,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND tenant_code = :tenantCode
                """, new MapSqlParameterSource()
                .addValue("id", job.id())
                .addValue("tenantCode", job.tenantCode())
                .addValue("status", job.status().name())
                .addValue("attempt", job.attempt())
                .addValue("availableAt", Timestamp.from(job.availableAt()))
                .addValue("leaseExpiresAt", job.leaseExpiresAt() == null
                        ? null : Timestamp.from(job.leaseExpiresAt()))
                .addValue("errorCode", job.errorCode()));
    }

    private Instant instant(Timestamp value) {
        return value == null ? Instant.EPOCH : value.toInstant();
    }

    private Instant instantOrNull(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
