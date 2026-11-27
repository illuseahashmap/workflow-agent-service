package io.github.illuseahashmap.agent.runtime.application;

import io.github.illuseahashmap.agent.runtime.domain.AgentFailure;

/** Runtime boundary exception carrying a typed failure, never a classification guess. */
public final class AgentExecutionException extends RuntimeException {

    private final AgentFailure failure;

    public AgentExecutionException(AgentFailure failure) {
        super(failure.safeMessage());
        this.failure = failure;
    }

    public AgentExecutionException(AgentFailure failure, Throwable cause) {
        super(failure.safeMessage(), cause);
        this.failure = failure;
    }

    public AgentFailure failure() {
        return failure;
    }
}
