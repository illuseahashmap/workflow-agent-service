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
        boolean abstained,
        List<List<EvidenceReference>> requiredEvidenceGroups
) {
    public RetrievalResult(RetrievalStatus status,List<Evidence> evidence,List<Citation> citations,String trace,
                           String strategy,List<String> warnings,boolean abstained) {
        this(status,evidence,citations,trace,strategy,warnings,abstained,List.of());
    }
    public RetrievalResult {
        status = Objects.requireNonNull(status, "status must not be null");
        evidence = List.copyOf(Objects.requireNonNullElse(evidence, List.of()));
        citations = List.copyOf(Objects.requireNonNullElse(citations, List.of()));
        retrievalTraceId = Objects.requireNonNull(retrievalTraceId, "retrievalTraceId must not be null");
        strategy = Objects.requireNonNullElse(strategy, "UNKNOWN");
        warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
        requiredEvidenceGroups = requiredEvidenceGroups.stream().map(List::copyOf).toList();
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

    /** Intersect obligations with evidence actually delivered, not pre-limit or pre-threshold hits. */
    public RetrievalResult requireCoverage() {
        var refs=evidence.stream().map(Evidence::reference).collect(java.util.stream.Collectors.toSet());
        if(status!=RetrievalStatus.SUCCESS || requiredEvidenceGroups.stream().allMatch(g->g.stream().anyMatch(refs::contains)))return this;
        return new RetrievalResult(RetrievalStatus.PARTIAL,evidence,citations,retrievalTraceId,strategy,
                List.of("One or more required authorized evidence groups are missing"),true,requiredEvidenceGroups);
    }
}
