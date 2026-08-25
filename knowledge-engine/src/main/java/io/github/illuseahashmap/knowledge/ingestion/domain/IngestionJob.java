package io.github.illuseahashmap.knowledge.ingestion.domain;

import java.time.Instant;
import java.util.Objects;

/** Leaseable, retryable ingestion state. A job is not considered complete until its index is committed. */
public record IngestionJob(long id, String tenantCode, String sourceCode, String documentHash,
                           IngestionStatus status, int attempt, Instant availableAt,
                           Instant leaseExpiresAt, String errorCode) {
    public IngestionJob {
        tenantCode = text(tenantCode, "tenantCode");
        sourceCode = text(sourceCode, "sourceCode");
        documentHash = text(documentHash, "documentHash");
        status = Objects.requireNonNull(status, "status must not be null");
        if (attempt < 0) throw new IllegalArgumentException("attempt must not be negative");
        availableAt = Objects.requireNonNull(availableAt, "availableAt must not be null");
    }
    public boolean terminal() { return status == IngestionStatus.SUCCEEDED || status == IngestionStatus.FAILED; }
    private static String text(String value, String name) {
        value = Objects.requireNonNull(value, name + " must not be null").trim();
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
