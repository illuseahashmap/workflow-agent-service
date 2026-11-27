package io.github.illuseahashmap.knowledge.retrieval.application.port;

import java.util.Optional;

/** Resolves the immutable retrieval profile bound to a published AgentVersion. */
public interface AgentRetrievalProfileBinding {
    Optional<Long> findProfileVersionId(String tenantCode, long agentVersionId);
}
