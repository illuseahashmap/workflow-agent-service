package io.github.illuseahashmap.agent.runtime.application.port;

import java.util.Optional;
import java.util.List;

public interface AgentToolPolicyRepository {

    Optional<AgentToolDefinition> findAuthorized(String tenantCode, String toolCode);

    default List<AgentToolDefinition> findAuthorized(String tenantCode, java.util.Set<String> toolCodes) {
        return toolCodes.stream()
                .map(toolCode -> findAuthorized(tenantCode, toolCode).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    AgentToolPolicyRepository ALLOW_ALL = (tenantCode, toolCode) ->
            Optional.of(new AgentToolDefinition(toolCode, toolCode, "{\"type\":\"object\"}", true));
}
