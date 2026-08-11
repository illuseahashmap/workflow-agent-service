package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.runtime.domain.AgentRun;

/** Application port for durable Agent lifecycle events. */
public interface AgentRunEventPublisher {
    void requested(AgentRun run);
    void completed(AgentRun run, String outputSnapshotJson);

    AgentRunEventPublisher NOOP = new AgentRunEventPublisher() {
        public void requested(AgentRun run) { }
        public void completed(AgentRun run, String outputSnapshotJson) { }
    };
}
