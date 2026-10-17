package io.github.illuseahashmap.knowledge.retrieval.application.port;

import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import java.util.List;

/** Tenant and index constrained vector search; infrastructure owns pgvector syntax. */
public interface VectorSearchPort {
    List<ChunkSearchPort.SearchHit> search(String tenantCode, RetrievalRequest request,
                                         List<Float> queryVector, String modelVersion, int limit);
}
