package io.github.illuseahashmap.agent.runtime.application;

/** Raised when a worker loses its fenced AgentRun lease while executing. */
public final class AgentLeaseLostException extends RuntimeException {

    public AgentLeaseLostException() {
        super("Agent execution lease was lost");
    }
}
