package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceGroundingPolicyTest {
    private final EvidenceGroundingPolicy policy = new EvidenceGroundingPolicy();
    private final EvidenceReference reference = new EvidenceReference(EvidenceType.CHUNK, "policy", "1", "chunk-1");

    @Test
    void rejectsEvidenceWithoutCitationBeforeModelSeesIt() {
        var result = new RetrievalResult(RetrievalStatus.SUCCESS,
                List.of(new ChunkEvidence(reference, "text", 0.8, "policy")), List.of(),
                "trace", "KEYWORD", List.of(), false);
        var gated = policy.apply(result, 0);
        assertThat(gated.status()).isEqualTo(RetrievalStatus.REJECTED);
        assertThat(gated.evidence()).isEmpty();
        assertThat(gated.abstained()).isTrue();
    }

    @Test
    void rejectsLowScoreDespiteWellFormedCitation() {
        var result = new RetrievalResult(RetrievalStatus.SUCCESS,
                List.of(new ChunkEvidence(reference, "text", 0.4, "policy")),
                List.of(new Citation("cite-1", reference, "text")),
                "trace", "VECTOR", List.of(), false);
        assertThat(policy.apply(result, 0.5).status()).isEqualTo(RetrievalStatus.REJECTED);
        assertThat(policy.apply(result, 0.3).status()).isEqualTo(RetrievalStatus.SUCCESS);
    }
}
