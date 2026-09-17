package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.List;

/** Immutable, tenant-owned retrieval policy selected by a published AgentVersion. */
public record RetrievalProfileVersion(
        long id,
        String tenantCode,
        String profileCode,
        int version,
        RetrievalRequest.StrategyHint strategy,
        int maxResults,
        double minimumScore,
        long indexVersionId,
        String status,
        List<String> knowledgeScopes,
        Instant createdAt
) {
    public RetrievalProfileVersion(long id, String tenantCode, String profileCode, int version,
                                   RetrievalRequest.StrategyHint strategy, int maxResults,
                                   double minimumScore, long indexVersionId, String status,
                                   Instant createdAt) {
        this(id, tenantCode, profileCode, version, strategy, maxResults, minimumScore,
                indexVersionId, status, List.of(), createdAt);
    }

    public RetrievalProfileVersion {
        if (id < 1 || indexVersionId < 1) {
            throw new IllegalArgumentException("profile and index IDs must be positive");
        }
        tenantCode = text(tenantCode, "tenantCode");
        profileCode = text(profileCode, "profileCode");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        if (maxResults < 1 || maxResults > 50) {
            throw new IllegalArgumentException("maxResults must be between 1 and 50");
        }
        if (!Double.isFinite(minimumScore) || minimumScore < 0) {
            throw new IllegalArgumentException("minimumScore must be finite and non-negative");
        }
        status = text(status, "status");
        knowledgeScopes = List.copyOf(Objects.requireNonNullElse(knowledgeScopes, List.of()));
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static String text(String value, String name) {
        value = Objects.requireNonNull(value, name + " must not be null").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
