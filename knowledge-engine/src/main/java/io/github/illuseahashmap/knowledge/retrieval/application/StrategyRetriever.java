package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetriever;
import io.github.illuseahashmap.knowledge.retrieval.domain.ChunkEvidence;
import io.github.illuseahashmap.knowledge.retrieval.domain.Citation;
import io.github.illuseahashmap.knowledge.retrieval.domain.Evidence;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalResult;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Profile-selected keyword/vector search and deterministic reciprocal-rank fusion. */
public final class StrategyRetriever implements KnowledgeRetriever {
    private static final double RRF_K = 60.0;
    private final KnowledgeRetriever keyword;
    private final KnowledgeRetriever vector;

    public StrategyRetriever(KnowledgeRetriever keyword, KnowledgeRetriever vector) {
        this.keyword = Objects.requireNonNull(keyword, "keyword");
        this.vector = vector;
    }

    @Override
    public RetrievalResult retrieve(String tenantCode, RetrievalRequest request) {
        return switch (request.strategyHint()) {
            case KEYWORD -> keyword.retrieve(tenantCode, request);
            case VECTOR -> requireVector().retrieve(tenantCode, request);
            case HYBRID -> hybrid(tenantCode, request);
            case AUTO -> keyword.retrieve(tenantCode, request);
            case GRAPH -> throw new IllegalArgumentException("Graph retrieval is not configured");
        };
    }

    private KnowledgeRetriever requireVector() {
        if (vector == null) {
            throw new IllegalStateException("Vector retrieval is not configured");
        }
        return vector;
    }

    private RetrievalResult hybrid(String tenantCode, RetrievalRequest request) {
        var keywordResult = keyword.retrieve(tenantCode, request);
        var vectorResult = requireVector().retrieve(tenantCode, request);
        if (keywordResult.status() == RetrievalStatus.REJECTED
                || vectorResult.status() == RetrievalStatus.REJECTED) {
            return new RetrievalResult(RetrievalStatus.REJECTED, List.of(), List.of(),
                    UUID.randomUUID().toString(), "HYBRID", List.of("Evidence type is unsupported"), true);
        }
        Map<EvidenceReference, RankedEvidence> merged = new LinkedHashMap<>();
        addRanks(merged, keywordResult, request.maxResults());
        addRanks(merged, vectorResult, request.maxResults());
        var ranked = merged.values().stream()
                .sorted(Comparator.comparingDouble(RankedEvidence::score).reversed()
                        .thenComparing(value -> value.evidence().reference().locator()))
                .limit(request.maxResults()).toList();
        if (ranked.isEmpty()) {
            return RetrievalResult.empty(UUID.randomUUID().toString(), "HYBRID");
        }
        List<Evidence> evidence = new ArrayList<>();
        List<Citation> citations = new ArrayList<>();
        for (var item : ranked) {
            var chunk = item.evidence();
            // Normalize the two-list RRF maximum to [0,1], so a published
            // minimumScore has meaningful semantics after fusion.
            evidence.add(new ChunkEvidence(chunk.reference(), chunk.text(),
                    Math.min(1.0, item.score() * (RRF_K + 1) / 2), chunk.title()));
            citations.add(item.citation());
        }
        return new RetrievalResult(RetrievalStatus.SUCCESS, evidence, citations,
                UUID.randomUUID().toString(), "HYBRID", List.of(), false);
    }

    private void addRanks(Map<EvidenceReference, RankedEvidence> merged,
                          RetrievalResult result, int limit) {
        Map<EvidenceReference, Citation> citations = new LinkedHashMap<>();
        result.citations().forEach(citation -> citations.put(citation.reference(), citation));
        int rank = 0;
        for (Evidence value : result.evidence()) {
            if (rank >= limit) {
                break;
            }
            if (!(value instanceof ChunkEvidence chunk)) {
                continue;
            }
            rank++;
            Citation citation = citations.get(chunk.reference());
            if (citation == null) {
                throw new IllegalStateException("Retriever returned evidence without citation");
            }
            double contribution = 1.0 / (RRF_K + rank);
            merged.merge(chunk.reference(), new RankedEvidence(chunk, citation, contribution),
                    (oldValue, next) -> new RankedEvidence(oldValue.evidence(), oldValue.citation(),
                            oldValue.score() + next.score()));
        }
    }

    private record RankedEvidence(ChunkEvidence evidence, Citation citation, double score) {}
}
