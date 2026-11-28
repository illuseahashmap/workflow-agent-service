package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.List;

/** Bounded audit metadata, without queries or source contents. Ranks follow reference order. */
public record QueryExpansionTrace(String policy, String outcome, List<Lane> lanes) {
    public QueryExpansionTrace {
        lanes = List.copyOf(lanes);
    }

    public record Lane(
            String queryFingerprint,
            String traceId,
            String status,
            long elapsedMillis,
            List<EvidenceReference> rankedReferences) {
        public Lane {
            rankedReferences = List.copyOf(rankedReferences);
        }
    }

    public static QueryExpansionTrace disabled() {
        return new QueryExpansionTrace("DISABLED", "SINGLE_QUERY", List.of());
    }
}
