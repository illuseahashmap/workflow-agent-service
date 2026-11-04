package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reproducible retrieval metrics for a frozen set of expected evidence references. */
public final class RetrievalEvaluation {
    public Score evaluate(List<EvidenceReference> expected, List<EvidenceReference> ranked, int k) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(ranked, "ranked");
        if (expected.isEmpty() || k < 1) {
            throw new IllegalArgumentException("Expected evidence and K must be non-empty");
        }
        Set<EvidenceReference> truth = Set.copyOf(expected);
        Set<EvidenceReference> seen = new HashSet<>();
        double reciprocalRank = 0;
        double dcg = 0;
        int hits = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            var reference = ranked.get(i);
            if (truth.contains(reference) && seen.add(reference)) {
                hits++;
                if (reciprocalRank == 0) {
                    reciprocalRank = 1.0 / (i + 1);
                }
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        double ideal = 0;
        for (int i = 0; i < Math.min(k, truth.size()); i++) {
            ideal += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return new Score((double) hits / truth.size(), hits > 0, reciprocalRank, dcg / ideal);
    }

    public record Score(double recallAtK, boolean hitAtK, double reciprocalRankAtK, double ndcgAtK) {}

    /** Source coverage and precise citation are separate metrics, not chunk-ID equality or group-inflated nDCG. */
    public SourceCoverage evaluateSourceCoverage(
            List<io.github.illuseahashmap.knowledge.retrieval.domain.SourceEvidenceAnchor> expected,
            List<io.github.illuseahashmap.knowledge.retrieval.domain.SourceEvidenceAnchor> returned, int k) {
        if (expected.isEmpty() || k < 1) { throw new IllegalArgumentException("Expected source anchors and K are required"); }
        var truth = Set.copyOf(expected);
        var candidates = returned.stream().limit(k).toList();
        long covered = truth.stream().filter(gold -> candidates.stream().anyMatch(actual -> actual.supports(gold, false))).count();
        long precise = truth.stream().filter(gold -> candidates.stream().anyMatch(actual -> actual.supports(gold, true))).count();
        return new SourceCoverage((double) covered / truth.size(), (double) precise / truth.size(), covered == truth.size());
    }

    public record SourceCoverage(double sourceCoverageAtK, double preciseCitationCoverageAtK, boolean allCovered) {}
}
