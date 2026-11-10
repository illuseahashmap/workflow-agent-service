package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.domain.ConditionalAmountQuestion;

import io.github.illuseahashmap.knowledge.retrieval.application.port.ChunkSearchPort;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetriever;
import io.github.illuseahashmap.knowledge.retrieval.domain.ChunkEvidence;
import io.github.illuseahashmap.knowledge.retrieval.domain.Citation;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceType;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.PolicyComparison;
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
        var groups=new java.util.EnumMap<PolicyComparison.Side,List<EvidenceReference>>(PolicyComparison.Side.class);
        var conditions=new java.util.EnumMap<ConditionalAmountQuestion.Role,List<EvidenceReference>>(ConditionalAmountQuestion.Role.class);
        if(ConditionalAmountQuestion.matches(request.query()))
            for(var role:ConditionalAmountQuestion.Role.values())conditions.put(role,new ArrayList<>());
        if(PolicyComparison.parse(request.query()).isPresent())
            for(var side:PolicyComparison.Side.values())groups.put(side,new ArrayList<>());
        for (ChunkSearchPort.SearchHit hit : hits) {
            var chunk = hit.chunk();
            EvidenceReference reference = new EvidenceReference(EvidenceType.CHUNK,
                    chunk.documentVersion().externalDocumentId(),
                    Integer.toString(chunk.documentVersion().version()), chunk.chunkId());
            evidence.add(ChunkEvidence.scored(reference, chunk.text(), hit.score(),
                    chunk.documentVersion().externalDocumentId(), "KEYWORD"));
            citations.add(new Citation("citation-" + chunk.chunkId(), reference, chunk.text()));
            if(groups.containsKey(hit.supportedSide()))groups.get(hit.supportedSide()).add(reference);
            if(conditions.containsKey(hit.conditionRole()))conditions.get(hit.conditionRole()).add(reference);
        }
        return new RetrievalResult(RetrievalStatus.SUCCESS, List.copyOf(evidence), List.copyOf(citations),
                traceId, "KEYWORD", List.of(), false,conditions.isEmpty()?List.copyOf(groups.values()):List.copyOf(conditions.values())).requireCoverage();
    }

    private boolean supportsRequestedEvidence(RetrievalRequest request) {
        return request.requiredEvidenceTypes().isEmpty()
                || (request.requiredEvidenceTypes().size() == 1
                && request.requiredEvidenceTypes().contains(EvidenceType.CHUNK));
    }
}
