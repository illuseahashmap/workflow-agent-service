package io.github.illuseahashmap.knowledge.retrieval.application.port;

import io.github.illuseahashmap.knowledge.ingestion.domain.Chunk;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;

import java.util.List;

/** Provider-neutral keyword search port; the adapter must apply tenant and active-index filters in storage. */
public interface ChunkSearchPort {
    List<SearchHit> search(String tenantCode, RetrievalRequest request, int limit);

    record SearchHit(Chunk chunk, double score) {
        public SearchHit {
            if (chunk == null) {
                throw new NullPointerException("chunk must not be null");
            }
            if (!Double.isFinite(score) || score < 0) {
                throw new IllegalArgumentException("score must be finite and non-negative");
            }
        }
    }
}
