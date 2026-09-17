package io.github.illuseahashmap.knowledge.retrieval.application.port;

import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalResult;

/** Application boundary used later by Agent tools and HTTP adapters. */
public interface KnowledgeRetrievalUseCase {

    RetrievalResult search(RetrievalRequest request);

    /** Runtime supplies the trusted published AgentVersion context; the model cannot choose it. */
    default RetrievalResult search(RetrievalRequest request, long agentVersionId) {
        return search(request);
    }

    default RetrievalResult search(RetrievalRequest request, long agentVersionId, String principalId) {
        return search(request, agentVersionId);
    }

    /**
     * Agent workers execute outside the HTTP request thread. The runtime therefore supplies the
     * trusted tenant captured by AgentRun instead of relying on a thread-local web context.
     */
    default RetrievalResult searchForAgent(
            RetrievalRequest request, String tenantCode, long agentVersionId, String principalId) {
        return search(request, agentVersionId, principalId);
    }
}
