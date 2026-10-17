package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelPort;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetriever;
import io.github.illuseahashmap.knowledge.retrieval.application.port.VectorSearchPort;
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

/** Query embedding and vector hit mapping, without exposing model or storage types to the domain. */
public final class VectorRetriever implements KnowledgeRetriever {
    private final EmbeddingModelPort embeddingModel;
    private final VectorSearchPort searchPort;

    public VectorRetriever(EmbeddingModelPort embeddingModel, VectorSearchPort searchPort) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.searchPort = Objects.requireNonNull(searchPort, "searchPort");
    }

    @Override
    public RetrievalResult retrieve(String tenantCode, RetrievalRequest request) {
        String traceId = UUID.randomUUID().toString();
        if (!request.requiredEvidenceTypes().isEmpty()
                && !request.requiredEvidenceTypes().equals(List.of(EvidenceType.CHUNK))) {
            return new RetrievalResult(RetrievalStatus.REJECTED, List.of(), List.of(), traceId,
                    "VECTOR", List.of("Vector retrieval only produces CHUNK evidence"), true);
        }
        var batch = embeddingModel.embedQuery(List.of(request.query()));
        if (batch.vectors().size() != 1) {
            throw new IllegalStateException("Query embedding must contain exactly one vector");
        }
        var hits = searchPort.search(tenantCode, request, batch.vectors().getFirst(),
                batch.modelVersion(), request.maxResults());
        if (hits.isEmpty()) {
            return RetrievalResult.empty(traceId, "VECTOR");
        }
        List<ChunkEvidence> evidence = new ArrayList<>();
        List<Citation> citations = new ArrayList<>();
        for (var hit : hits) {
            var chunk = hit.chunk();
            var reference = new EvidenceReference(EvidenceType.CHUNK,
                    chunk.documentVersion().externalDocumentId(),
                    Integer.toString(chunk.documentVersion().version()), chunk.chunkId());
            evidence.add(new ChunkEvidence(reference, chunk.text(), hit.score(),
                    chunk.documentVersion().externalDocumentId()));
            citations.add(new Citation("citation-" + chunk.chunkId(), reference, chunk.text()));
        }
        return new RetrievalResult(RetrievalStatus.SUCCESS, List.copyOf(evidence), List.copyOf(citations),
                traceId, "VECTOR", List.of(), false);
    }
}
