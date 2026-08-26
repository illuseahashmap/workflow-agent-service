package io.github.illuseahashmap.knowledge.catalog.domain;

import java.time.Instant;
import java.util.Objects;

/** Immutable source snapshot identified by a content hash. */
public record DocumentVersion(
        long id, String tenantCode, String sourceCode, String externalDocumentId,
        int version, String contentHash, String status, Instant createdAt
) {
    public DocumentVersion {
        tenantCode = text(tenantCode, "tenantCode");
        sourceCode = text(sourceCode, "sourceCode");
        externalDocumentId = text(externalDocumentId, "externalDocumentId");
        contentHash = text(contentHash, "contentHash");
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
