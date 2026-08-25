package io.github.illuseahashmap.knowledge.retrieval.application.port;

import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalResult;

/** Application boundary used later by Agent tools and HTTP adapters. */
public interface KnowledgeRetrievalUseCase {

    RetrievalResult search(RetrievalRequest request);
}
