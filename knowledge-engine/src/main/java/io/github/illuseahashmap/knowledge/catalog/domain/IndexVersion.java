package io.github.illuseahashmap.knowledge.catalog.domain;

import java.time.Instant;
import java.util.Objects;

/** Search index build, separated from document ingestion for rebuildability. */
public record IndexVersion(long id, String tenantCode, String sourceCode, int version,
                           String embeddingModel, String status, Instant createdAt) {
    public IndexVersion {
        tenantCode = text(tenantCode, "tenantCode");
        sourceCode = text(sourceCode, "sourceCode");
        embeddingModel = text(embeddingModel, "embeddingModel");
        status = text(status, "status");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
    }
    private static String text(String value, String name) {
        value = Objects.requireNonNull(value, name + " must not be null").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
