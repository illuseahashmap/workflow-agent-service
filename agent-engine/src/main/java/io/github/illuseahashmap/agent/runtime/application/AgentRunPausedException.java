package io.github.illuseahashmap.agent.runtime.application;

/** Stops the current worker after a requested pause was durably applied. */
public class AgentRunPausedException extends RuntimeException {

    public AgentRunPausedException() {
        super("Agent run paused at a durable execution boundary");
    }
}
