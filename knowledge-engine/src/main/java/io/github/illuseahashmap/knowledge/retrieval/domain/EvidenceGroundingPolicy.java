package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.HashSet;
import java.util.List;

/** Deterministic evidence gate applied before evidence is returned to a model. */
public final class EvidenceGroundingPolicy {
    public RetrievalResult apply(RetrievalResult result, double minimumScore) {
        return apply(result, ScorePolicy.forStrategy(result.strategy(), minimumScore));
    }

    public RetrievalResult apply(RetrievalResult result, ScorePolicy policy) {
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
        if (result.evidence().stream().anyMatch(evidence -> !(evidence instanceof ChunkEvidence chunk)
                || chunk.scoreKind() != policy.kind() || !chunk.scoreVersion().equals(policy.version()))) {
            return rejected(result, "Evidence score scale does not match the frozen profile policy");
        }
        var selected = result.evidence().stream().filter(evidence ->
                policy.accepts(((ChunkEvidence) evidence).scoring())).toList();
        if (selected.isEmpty()) { return rejected(result, "Evidence score is below the published profile threshold"); }
        var selectedReferences = selected.stream().map(Evidence::reference).collect(java.util.stream.Collectors.toSet());
        return new RetrievalResult(result.status(), selected,
                result.citations().stream().filter(citation -> selectedReferences.contains(citation.reference())).toList(),
                result.retrievalTraceId(), result.strategy(), result.warnings(), result.abstained());
    }

    /** Evidence groups are selected atomically: support segments never compete with their anchor for a slot. */
    public List<EvidenceGroup> selectGroups(List<EvidenceGroup> groups, ScorePolicy policy, int maximumGroups) {
        if (maximumGroups < 1) { throw new IllegalArgumentException("Group limit must be positive"); }
        if (groups.stream().anyMatch(group -> group.selectionScore() == null
                || group.selectionScore().kind() != policy.kind()
                || !group.selectionScore().version().equals(policy.version()))) {
            throw new IllegalArgumentException("Group score scale does not match frozen policy");
        }
        return groups.stream().filter(group -> policy.accepts(group.selectionScore()))
                .sorted(java.util.Comparator.comparingDouble((EvidenceGroup group) -> group.selectionScore().value()).reversed()
                        .thenComparing(EvidenceGroup::groupId)).limit(maximumGroups).toList();
    }

    private RetrievalResult rejected(RetrievalResult original, String reason) {
        return new RetrievalResult(RetrievalStatus.REJECTED, List.of(), List.of(),
                original.retrievalTraceId(), original.strategy(), List.of(reason), true);
    }
}
