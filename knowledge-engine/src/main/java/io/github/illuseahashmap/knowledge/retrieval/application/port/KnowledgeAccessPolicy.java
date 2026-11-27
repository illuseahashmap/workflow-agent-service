package io.github.illuseahashmap.knowledge.retrieval.application.port;

import java.util.List;

/**
 * Application port for resolving the final knowledge scope.
 * Implementations combine AgentVersion, RetrievalProfile and principal permissions.
 */
public interface KnowledgeAccessPolicy {

    List<String> authorize(String tenantCode, List<String> requestedScopes);

    /** Resolves the intersection of the published profile and the initiating principal's grants. */
    default List<String> authorize(String tenantCode, String principalId,
                                   List<String> profileScopes, List<String> requestedScopes) {
        return authorize(tenantCode, requestedScopes == null || requestedScopes.isEmpty()
                ? profileScopes : requestedScopes);
    }
}
