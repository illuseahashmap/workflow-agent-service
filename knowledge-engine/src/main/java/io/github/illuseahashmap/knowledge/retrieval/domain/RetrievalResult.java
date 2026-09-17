package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.List;
import java.util.Objects;

/** Immutable retrieval result with explicit abstention and traceability semantics. */
public record RetrievalResult(
        RetrievalStatus status,
        List<Evidence> evidence,
        List<Citation> citations,
        String retrievalTraceId,
        String strategy,
        List<String> warnings,
        boolean abstained
) {
    public RetrievalResult {
        status = Objects.requireNonNull(status, "status must not be null");
        evidence = List.copyOf(Objects.requireNonNullElse(evidence, List.of()));
        citations = List.copyOf(Objects.requireNonNullElse(citations, List.of()));
        retrievalTraceId = Objects.requireNonNull(retrievalTraceId, "retrievalTraceId must not be null");
        strategy = Objects.requireNonNullElse(strategy, "UNKNOWN");
        warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
        if (status == RetrievalStatus.SUCCESS && evidence.isEmpty()) {
            throw new IllegalArgumentException("SUCCESS retrieval must contain evidence");
        }
        if (status == RetrievalStatus.EMPTY && !evidence.isEmpty()) {
            throw new IllegalArgumentException("EMPTY retrieval must not contain evidence");
        }
    }

    public static RetrievalResult empty(String traceId, String strategy) {
        return new RetrievalResult(RetrievalStatus.EMPTY, List.of(), List.of(), traceId,
                strategy, List.of(), true);
    }
}
