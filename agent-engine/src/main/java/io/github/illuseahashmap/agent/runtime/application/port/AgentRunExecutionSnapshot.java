package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.runtime.domain.AgentRun;

public record AgentRunExecutionSnapshot(AgentRun run, String inputSnapshotJson) {
}
