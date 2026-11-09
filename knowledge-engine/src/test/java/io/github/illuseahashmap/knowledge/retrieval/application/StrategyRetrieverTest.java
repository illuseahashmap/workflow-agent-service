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
    void vectorSimilarityCannotFillOrContaminateAComparison() {
        var request=new RetrievalRequest("内部交接能否套用外部运输制度",List.of("policy"),List.of(),null,5,
                RetrievalRequest.StrategyHint.HYBRID,0,List.of());
        var pair=result("KEYWORD","a","b");
        var verified=new RetrievalResult(pair.status(),pair.evidence(),pair.citations(),pair.retrievalTraceId(),"KEYWORD",List.of(),false,
                pair.evidence().stream().map(e->List.of(e.reference())).toList());
        KnowledgeRetriever vector=(tenant,r)->result("VECTOR","other-amount-band","b");
        var merged=new StrategyRetriever((tenant,r)->verified,vector).retrieve("t",request);
        assertThat(merged.status()).isEqualTo(RetrievalStatus.SUCCESS);
        assertThat(merged.evidence()).extracting(e->e.reference().locator()).containsExactlyInAnyOrder("a","b");
        var absent=RetrievalResult.empty("no-scope","KEYWORD");
        var incomplete=new StrategyRetriever((tenant,r)->absent,vector).retrieve("t",request);
        assertThat(incomplete.abstained()).isTrue();
        var vectorOnly=new RetrievalRequest(request.query(),request.knowledgeScopes(),List.of(),null,5,
                RetrievalRequest.StrategyHint.VECTOR,0,List.of());
        assertThat(new StrategyRetriever((t,r)->verified,vector).retrieve("t",vectorOnly).abstained()).isTrue();
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
