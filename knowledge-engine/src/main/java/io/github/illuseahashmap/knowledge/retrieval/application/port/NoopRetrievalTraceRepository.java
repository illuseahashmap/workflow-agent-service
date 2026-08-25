package io.github.illuseahashmap.knowledge.retrieval.application.port;

import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalTrace;

/** Explicit no-op adapter for deployments that have not enabled retrieval audit storage. */
public final class NoopRetrievalTraceRepository implements RetrievalTraceRepository {
    @Override
    public void save(RetrievalTrace trace) {
        // Deliberately empty: the application contract remains independent of storage.
    }
}
