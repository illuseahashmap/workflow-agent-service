package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable audit facts for one authorized retrieval, without storing document contents. */
public record RetrievalTrace(
        String traceId,
        String tenantCode,
        String queryFingerprint,
        List<String> authorizedScopes,
        RetrievalStatus status,
        String strategy,
        int evidenceCount,
        Instant createdAt
) {
    public RetrievalTrace {
        traceId = requireText(traceId, "traceId");
        tenantCode = requireText(tenantCode, "tenantCode");
        queryFingerprint = requireText(queryFingerprint, "queryFingerprint");
        authorizedScopes = List.copyOf(Objects.requireNonNullElse(authorizedScopes, List.of()));
        status = Objects.requireNonNull(status, "status must not be null");
        strategy = requireText(strategy, "strategy");
        if (evidenceCount < 0) {
            throw new IllegalArgumentException("evidenceCount must not be negative");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name + " must not be null").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
