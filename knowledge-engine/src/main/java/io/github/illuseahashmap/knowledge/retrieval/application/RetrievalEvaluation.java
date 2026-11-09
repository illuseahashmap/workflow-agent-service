package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reproducible retrieval metrics for a frozen set of expected evidence references. */
public final class RetrievalEvaluation {
    /** AND across groups, OR within each group. Unjudged candidates are not negative labels. */
    public GroupCoverage evaluateGroups(List<List<String>> groups, java.util.Map<String,Integer> judgments,
            Set<String> forbidden, List<String> ranked, int k) {
        if (k < 1 || judgments.entrySet().stream().anyMatch(e ->
                e.getValue() < 0 || e.getValue() > 2 || forbidden.contains(e.getKey())))
            throw new IllegalArgumentException("Invalid retrieval judgments");
        var missing = new java.util.ArrayList<List<String>>();
        var top = ranked.stream().limit(k).toList();
        for (var group : groups) {
            if (group.isEmpty() || Set.copyOf(group).size()!=group.size()
                    || group.stream().anyMatch(id -> !Integer.valueOf(2).equals(judgments.get(id))))
                throw new IllegalArgumentException("Each required alternative needs an authorized decisive judgment");
            if (java.util.Collections.disjoint(group, top)) missing.add(List.copyOf(group));
        }
        return new GroupCoverage(groups.size()-missing.size(), List.copyOf(missing),
                top.stream().filter(forbidden::contains).distinct().toList(),
                top.stream().filter(id -> !judgments.containsKey(id) && !forbidden.contains(id)).count());
    }
    public record GroupCoverage(int matchedGroups, List<List<String>> missing, List<String> leaks, long unjudged) {
        public boolean applicable() { return matchedGroups > 0 || !missing.isEmpty(); }
        public boolean complete() { return applicable() && missing.isEmpty(); }
    }
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
