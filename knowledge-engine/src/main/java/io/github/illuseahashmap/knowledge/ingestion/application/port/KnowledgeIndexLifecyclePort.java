package io.github.illuseahashmap.knowledge.ingestion.application.port;

import io.github.illuseahashmap.knowledge.catalog.domain.IndexVersion;

/** Controls candidate validation and atomic activation independently from index construction. */
public interface KnowledgeIndexLifecyclePort {
    void activate(IndexVersion indexVersion);
}
