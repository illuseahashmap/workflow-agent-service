package io.github.illuseahashmap.agent.runtime.application.port;

import java.util.Optional;

public interface AgentToolPolicyRepository {

    Optional<AgentToolDefinition> findAuthorized(String tenantCode, String toolCode);

    AgentToolPolicyRepository ALLOW_ALL = (tenantCode, toolCode) ->
            Optional.of(new AgentToolDefinition(toolCode, toolCode, "{\"type\":\"object\"}", true));
}
