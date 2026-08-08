package io.github.illuseahashmap.agent.runtime.domain;

/**
 * Indicates that a command violates the Agent run lifecycle contract.
 */
public final class AgentRunTransitionException extends IllegalStateException {

    public AgentRunTransitionException(String message) {
        super(message);
    }
}
