package io.github.illuseahashmap.workflow.agent;

/** Marks a completion event that cannot succeed without changing its frozen contract. */
public class AgentCompletionContractException extends RuntimeException {

    public AgentCompletionContractException(String message) {
        super(message);
    }

    public AgentCompletionContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
