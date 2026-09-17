package io.github.illuseahashmap.agent.runtime.domain;

/**
 * Lifecycle states of one logical Agent execution.
 */
public enum AgentRunStatus {

    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == TIMED_OUT || this == CANCELLED;
    }
}
