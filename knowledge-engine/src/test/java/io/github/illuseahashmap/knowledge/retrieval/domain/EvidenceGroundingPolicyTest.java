package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceGroundingPolicyTest {
    private final EvidenceGroundingPolicy policy = new EvidenceGroundingPolicy();
    private final EvidenceReference reference = new EvidenceReference(EvidenceType.CHUNK, "policy", "1", "chunk-1");

    @Test
    void scoreCutoffCannotRemoveOneComparisonSideAndStillSucceed() {
        var other=new EvidenceReference(EvidenceType.CHUNK,"second","1","second-clause");
        var both=new RetrievalResult(RetrievalStatus.SUCCESS,
                List.of(ChunkEvidence.scored(reference,"A",0.9,"A","KEYWORD"),ChunkEvidence.scored(other,"B",0.2,"B","KEYWORD")),
                List.of(new Citation("a",reference,"A"),new Citation("b",other,"B")),"trace","KEYWORD",List.of(),false,
                List.of(List.of(reference),List.of(other)));
        assertThat(policy.apply(both,0).status()).isEqualTo(RetrievalStatus.SUCCESS);
        var partial=policy.apply(both,0.5);
        assertThat(partial.status()).isEqualTo(RetrievalStatus.PARTIAL);
        assertThat(partial.abstained()).isTrue();
    }

    @Test
    void rejectsEvidenceWithoutCitationBeforeModelSeesIt() {
        var result = new RetrievalResult(RetrievalStatus.SUCCESS,
                List.of(ChunkEvidence.scored(reference, "text", 0.8, "policy", "KEYWORD")), List.of(),
                "trace", "KEYWORD", List.of(), false);
        var gated = policy.apply(result, 0);
        assertThat(gated.status()).isEqualTo(RetrievalStatus.REJECTED);
        assertThat(gated.evidence()).isEmpty();
        assertThat(gated.abstained()).isTrue();
    }

    @Test
    void rejectsLowScoreDespiteWellFormedCitation() {
        var result = new RetrievalResult(RetrievalStatus.SUCCESS,
                List.of(ChunkEvidence.scored(reference, "text", 0.4, "policy", "VECTOR")),
                List.of(new Citation("cite-1", reference, "text")),
                "trace", "VECTOR", List.of(), false);
        assertThat(policy.apply(result, 0.5).status()).isEqualTo(RetrievalStatus.REJECTED);
        assertThat(policy.apply(result, 0.3).status()).isEqualTo(RetrievalStatus.SUCCESS);
    }

    @Test
    void rejectsUnknownOrDifferentScoreScalesEvenWhenTheirNumericValueIsHigh() {
        var result = new RetrievalResult(RetrievalStatus.SUCCESS,
                List.of(ChunkEvidence.scored(reference, "text", 0.99, "policy", "VECTOR")),
                List.of(new Citation("cite-1", reference, "text")), "trace", "VECTOR", List.of(), false);
        assertThat(policy.apply(result, ScorePolicy.forStrategy("HYBRID", 0.1)).status()).isEqualTo(RetrievalStatus.REJECTED);
        var wrongVersion = new ScorePolicy(EvidenceScore.Kind.COSINE_SIMILARITY, "different-model-calibration", 0.1);
        assertThat(policy.apply(result, wrongVersion).status()).isEqualTo(RetrievalStatus.REJECTED);
    }

    @Test
    void removesLowQualityEvidenceAndItsCitationRatherThanLettingOneHitApproveEverything() {
        var low = new EvidenceReference(EvidenceType.CHUNK, "policy", "1", "low");
        var result = new RetrievalResult(RetrievalStatus.SUCCESS,
                List.of(ChunkEvidence.scored(reference, "high", 0.9, "policy", "VECTOR"),
                        ChunkEvidence.scored(low, "low", 0.1, "policy", "VECTOR")),
                List.of(new Citation("high", reference, "high"), new Citation("low", low, "low")),
                "trace", "VECTOR", List.of(), false);
        var selected = policy.apply(result, 0.5);
        assertThat(selected.evidence()).hasSize(1);
        assertThat(selected.citations()).extracting(Citation::reference).containsExactly(reference);
    }

    @Test
    void selectsWholeGroupsByTheirExplicitRerankScoreAndRejectsAbsentCalibration() {
        var support = new EvidenceReference(EvidenceType.CHUNK, "policy", "1", "units");
        var scorePolicy = new ScorePolicy(EvidenceScore.Kind.RERANK, "model-x/calibration-1", 0.6);
        var group = new EvidenceGroup("group", List.of(reference, support), "含税，万元。0.2<金额<=2",
                new EvidenceScore(EvidenceScore.Kind.RERANK, "model-x/calibration-1", 0.9));
        assertThat(policy.selectGroups(List.of(group), scorePolicy, 1)).containsExactly(group);
        assertThat(policy.selectGroups(List.of(group), scorePolicy, 1).getFirst().sources()).hasSize(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> policy.selectGroups(List.of(
                new EvidenceGroup("unscored", List.of(reference), "text", null)), scorePolicy, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
