package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceType;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RetrievalEvaluationTest {
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
}
