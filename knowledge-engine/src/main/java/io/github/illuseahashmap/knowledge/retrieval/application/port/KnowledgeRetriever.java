package io.github.illuseahashmap.knowledge.retrieval.application.port;

import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalResult;

/** Infrastructure-neutral retrieval port; implementations must enforce tenant scope. */
public interface KnowledgeRetriever {

    RetrievalResult retrieve(String tenantCode, RetrievalRequest request);
}
