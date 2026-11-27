package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.runtime.domain.AgentRun;

public record AgentRunExecutionSnapshot(AgentRun run, String inputSnapshotJson, String requestedBy) {
    public AgentRunExecutionSnapshot(AgentRun run, String inputSnapshotJson) {
        this(run, inputSnapshotJson, null);
    }
}
