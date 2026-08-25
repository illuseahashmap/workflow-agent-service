package io.github.illuseahashmap.knowledge.retrieval.application.port;

import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalTrace;

/** Application port for durable retrieval audit facts. */
public interface RetrievalTraceRepository {
    void save(RetrievalTrace trace);
}
