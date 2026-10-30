package io.github.illuseahashmap.agent.runtime.application.port;

import java.util.List;

/** Immutable tool metadata captured in the AgentVersion publication transaction. */
public interface AgentToolSnapshotRepository {
    void freeze(String tenantCode, long versionId, List<AgentToolDefinition> tools);
    List<AgentToolDefinition> find(String tenantCode, long versionId);
}
