package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetriever;
import io.github.illuseahashmap.knowledge.retrieval.domain.ChunkEvidence;
import io.github.illuseahashmap.knowledge.retrieval.domain.Citation;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference;
import io.github.illuseahashmap.knowledge.retrieval.domain.Evidence;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceType;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalResult;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyRetrieverTest {
    private static RetrievalRequest request(RetrievalRequest.StrategyHint strategy) {
        return new RetrievalRequest("采购审批", List.of("policy"), List.of(), null, 3,
                strategy, 0, List.of(), 23);
    }

    private static RetrievalResult result(String strategy, String... ids) {
        List<Evidence> evidence = java.util.Arrays.stream(ids).map(id -> {
            var ref = new EvidenceReference(EvidenceType.CHUNK, "handbook", "1", id);
            return (Evidence) new ChunkEvidence(ref, id, 0.9, "handbook");
        }).toList();
        return new RetrievalResult(RetrievalStatus.SUCCESS, evidence,
                evidence.stream().map(chunk -> new Citation("cite-" + chunk.reference().locator(),
                        chunk.reference(), ((ChunkEvidence) chunk).text())).toList(),
                "trace-" + strategy, strategy, List.of(), false);
    }

    @Test
    void hybridDeduplicatesByStableReferenceAndUsesBothRankings() {
        KnowledgeRetriever keyword = (tenant, request) -> result("KEYWORD", "a", "b");
        KnowledgeRetriever vector = (tenant, request) -> result("VECTOR", "b", "c");

        RetrievalResult merged = new StrategyRetriever(keyword, vector).retrieve("tenant-a",
                request(RetrievalRequest.StrategyHint.HYBRID));

        assertThat(merged.strategy()).isEqualTo("HYBRID");
        assertThat(merged.evidence()).hasSize(3);
        assertThat(merged.evidence().getFirst().reference().locator()).isEqualTo("b");
        assertThat(((ChunkEvidence) merged.evidence().getFirst()).score()).isBetween(0.9, 1.0);
        assertThat(merged.citations()).hasSize(3);
    }

    @Test
    void vectorStrategyFailsExplicitlyWhenCapabilityIsDisabled() {
        var retriever = new StrategyRetriever((tenant, request) -> result("KEYWORD", "a"), null);
        assertThatThrownBy(() -> retriever.retrieve("tenant-a",
                request(RetrievalRequest.StrategyHint.VECTOR)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not configured");
    }
}
