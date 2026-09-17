package io.github.illuseahashmap.workflow.process.application;

/** Permanent contract violation that must not be retried by the transport. */
public class AgentCompletionContractException extends RuntimeException {

    public AgentCompletionContractException(String message) {
        super(message);
    }

    public AgentCompletionContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
