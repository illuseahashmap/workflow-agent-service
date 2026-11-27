package io.github.illuseahashmap.knowledge.retrieval.application.port;

import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalProfileVersion;

import java.util.Optional;

/** Resolves only an immutable published profile selected by trusted AgentVersion context. */
public interface RetrievalProfileRepository {
    Optional<RetrievalProfileVersion> findPublished(String tenantCode, long profileVersionId);
}
