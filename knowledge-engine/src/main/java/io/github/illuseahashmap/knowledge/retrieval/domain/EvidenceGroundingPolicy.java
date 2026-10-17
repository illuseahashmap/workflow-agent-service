package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.HashSet;
import java.util.List;

/** Deterministic evidence gate applied before evidence is returned to a model. */
public final class EvidenceGroundingPolicy {
    public RetrievalResult apply(RetrievalResult result, double minimumScore) {
        if (result.status() != RetrievalStatus.SUCCESS) {
            return result;
        }
        var references = new HashSet<EvidenceReference>();
        for (Evidence evidence : result.evidence()) {
            references.add(evidence.reference());
        }
        var cited = new HashSet<EvidenceReference>();
        for (Citation citation : result.citations()) {
            if (!references.contains(citation.reference()) || !cited.add(citation.reference())) {
                return rejected(result, "Citation does not match this retrieval's evidence");
            }
        }
        if (!cited.containsAll(references)) {
            return rejected(result, "Evidence is missing a citation");
        }
        boolean meetsThreshold = result.evidence().stream().anyMatch(evidence ->
                evidence instanceof ChunkEvidence chunk && chunk.score() >= minimumScore);
        if (!meetsThreshold) {
            return rejected(result, "Evidence score is below the published profile threshold");
        }
        return result;
    }

    private RetrievalResult rejected(RetrievalResult original, String reason) {
        return new RetrievalResult(RetrievalStatus.REJECTED, List.of(), List.of(),
                original.retrievalTraceId(), original.strategy(), List.of(reason), true);
    }
}
