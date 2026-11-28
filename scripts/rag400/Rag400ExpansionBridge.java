package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetriever;
import io.github.illuseahashmap.knowledge.retrieval.domain.*;

/** Test-only access to the actual production query-fusion implementation. */
public final class Rag400ExpansionBridge {
    public record Result(RetrievalResult result, QueryExpansionTrace trace) {}
    public static Result retrieve(KnowledgeRetriever retriever, String tenant, RetrievalRequest request) {
        var outcome = new QueryExpansionRetrieval(retriever).retrieve(
                tenant, request, ScorePolicy.forStrategy("HYBRID", 0));
        return new Result(outcome.result(), outcome.trace());
    }
}
