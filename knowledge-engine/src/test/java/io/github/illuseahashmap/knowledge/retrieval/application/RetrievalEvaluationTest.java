package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceType;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RetrievalEvaluationTest {
    @org.junit.jupiter.api.Test
    void requiredGroupsAreConjunctiveWithAlternativesAndSeparateLeakAccounting() {
        var scorer=new RetrievalEvaluation();
        var groups=java.util.List.of(java.util.List.of("clause","equivalent"),java.util.List.of("exception"));
        var labels=java.util.Map.of("clause",2,"equivalent",2,"exception",2,"background",1);
        var ranked=java.util.List.of("equivalent","equivalent","unjudged","secret","exception");
        var r=scorer.evaluateGroups(groups,labels,java.util.Set.of("secret"),ranked,4);
        org.assertj.core.api.Assertions.assertThat(r.matchedGroups()).isOne();
        org.assertj.core.api.Assertions.assertThat(r.unjudged()).isOne();
        org.assertj.core.api.Assertions.assertThat(r.leaks()).containsExactly("secret");
        org.assertj.core.api.Assertions.assertThat(r.missing()).containsExactly(java.util.List.of("exception"));
        org.assertj.core.api.Assertions.assertThat(scorer.evaluateGroups(groups,labels,java.util.Set.of("secret"),ranked,5).complete()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->scorer.evaluateGroups(groups,labels,java.util.Set.of("exception"),ranked,4))
                .isInstanceOf(IllegalArgumentException.class);
        var absence=scorer.evaluateGroups(java.util.List.of(),java.util.Map.of(),java.util.Set.of("secret"),ranked,5);
        org.assertj.core.api.Assertions.assertThat(absence.applicable()).isFalse();
        org.assertj.core.api.Assertions.assertThat(absence.complete()).isFalse();
        org.assertj.core.api.Assertions.assertThat(absence.leaks()).containsExactly("secret");
    }
    @Test
    void computesRankSensitiveMetricsWithoutCountingDuplicateHits() {
        var a = ref("a");
        var b = ref("b");
        var unrelated = ref("other");
        var score = new RetrievalEvaluation().evaluate(List.of(a, b),
                List.of(unrelated, a, a, b), 3);
        assertThat(score.recallAtK()).isEqualTo(0.5);
        assertThat(score.hitAtK()).isTrue();
        assertThat(score.reciprocalRankAtK()).isEqualTo(0.5);
        assertThat(score.ndcgAtK()).isBetween(0.38, 0.39);
    }

    private EvidenceReference ref(String locator) {
        return new EvidenceReference(EvidenceType.CHUNK, "document", "1", locator);
    }

    @Test
    void distinguishesBroadPdfCoverageFromPreciseCitationAndRejectsAnotherArtifact() {
        var required = new io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceLocator(
                1, io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceLocator.Kind.PDF_REGION, null,
                "a".repeat(64), "element", List.of(new io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceLocator.Region(
                        0, 0.2, 0.2, 0.4, 0.4)), List.of(), List.of(), null);
        var broad = new io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceLocator(
                1, required.kind(), null, required.artifactHash(), "element", List.of(
                        new io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceLocator.Region(0, 0, 0, 1, 1)),
                List.of(), List.of(), null);
        var gold = new io.github.illuseahashmap.knowledge.retrieval.domain.SourceEvidenceAnchor("policy", "1", "b".repeat(64), required);
        var parent = new io.github.illuseahashmap.knowledge.retrieval.domain.SourceEvidenceAnchor("policy", "1", "b".repeat(64), broad);
        var score = new RetrievalEvaluation().evaluateSourceCoverage(List.of(gold), List.of(parent, parent), 2);
        assertThat(score.sourceCoverageAtK()).isEqualTo(1);
        assertThat(score.preciseCitationCoverageAtK()).isZero();
        var wrongVersion = new io.github.illuseahashmap.knowledge.retrieval.domain.SourceEvidenceAnchor("policy", "2", "b".repeat(64), required);
        assertThat(new RetrievalEvaluation().evaluateSourceCoverage(List.of(gold), List.of(wrongVersion), 1).allCovered()).isFalse();
    }
}
