package io.github.illuseahashmap.agent.runtime.application.port;

import java.util.Optional;

/** Resolves governed tools whose implementation is backed by a versioned external catalog. */
public interface AgentToolResolver {
    Optional<AgentTool> resolve(String toolCode);
}
