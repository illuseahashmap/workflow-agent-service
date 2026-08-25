package io.github.illuseahashmap.knowledge.retrieval.application.port;

import java.util.List;

/**
 * Application port for resolving the final knowledge scope.
 * Implementations combine AgentVersion, RetrievalProfile and principal permissions.
 */
public interface KnowledgeAccessPolicy {

    List<String> authorize(String tenantCode, List<String> requestedScopes);
}
