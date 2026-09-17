package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.application.port.ChunkSearchPort;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetriever;
import io.github.illuseahashmap.knowledge.retrieval.domain.ChunkEvidence;
import io.github.illuseahashmap.knowledge.retrieval.domain.Citation;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceType;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalResult;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** First production retrieval strategy: bounded keyword hits mapped to neutral Evidence and Citation. */
public final class KeywordRetriever implements KnowledgeRetriever {
    private final ChunkSearchPort searchPort;

    public KeywordRetriever(ChunkSearchPort searchPort) {
        this.searchPort = Objects.requireNonNull(searchPort, "searchPort must not be null");
    }

    @Override
    public RetrievalResult retrieve(String tenantCode, RetrievalRequest request) {
        Objects.requireNonNull(tenantCode, "tenantCode must not be null");
        Objects.requireNonNull(request, "request must not be null");
        String traceId = UUID.randomUUID().toString();
        if (!supportsRequestedEvidence(request)) {
            return new RetrievalResult(RetrievalStatus.REJECTED, List.of(), List.of(), traceId,
                    "KEYWORD", List.of("Keyword retrieval only produces CHUNK evidence"), true);
        }
        List<ChunkSearchPort.SearchHit> hits = searchPort.search(tenantCode, request, request.maxResults());
        if (hits.isEmpty()) {
            return RetrievalResult.empty(traceId, "KEYWORD");
        }
        List<ChunkEvidence> evidence = new ArrayList<>();
        List<Citation> citations = new ArrayList<>();
        for (ChunkSearchPort.SearchHit hit : hits) {
            var chunk = hit.chunk();
            EvidenceReference reference = new EvidenceReference(EvidenceType.CHUNK,
                    chunk.documentVersion().externalDocumentId(),
                    Integer.toString(chunk.documentVersion().version()), chunk.chunkId());
            evidence.add(new ChunkEvidence(reference, chunk.text(), hit.score(),
                    chunk.documentVersion().externalDocumentId()));
            citations.add(new Citation("citation-" + chunk.chunkId(), reference, chunk.text()));
        }
        return new RetrievalResult(RetrievalStatus.SUCCESS, List.copyOf(evidence), List.copyOf(citations),
                traceId, "KEYWORD", List.of(), false);
    }

    private boolean supportsRequestedEvidence(RetrievalRequest request) {
        return request.requiredEvidenceTypes().isEmpty()
                || (request.requiredEvidenceTypes().size() == 1
                && request.requiredEvidenceTypes().contains(EvidenceType.CHUNK));
    }
}
