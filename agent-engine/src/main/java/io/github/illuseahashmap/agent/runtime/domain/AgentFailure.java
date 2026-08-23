package io.github.illuseahashmap.agent.runtime.domain;

import java.util.Objects;

/**
 * A stable, serializable description of an Agent execution failure.
 *
 * <p>The runtime never needs to interpret an arbitrary exception message to
 * select a recovery action. Adapters and validators create this value at the
 * boundary where the failure is known.</p>
 */
public record AgentFailure(
        String errorCode,
        AgentFailureCategory category,
        boolean retryable,
        ResultStatus resultStatus,
        String safeMessage
) {

    public AgentFailure {
        errorCode = Objects.requireNonNullElse(errorCode, "AGENT_EXECUTION_ERROR");
        category = Objects.requireNonNullElse(category, AgentFailureCategory.EXECUTION_UNEXPECTED);
        resultStatus = Objects.requireNonNullElse(resultStatus, ResultStatus.FAILED);
        safeMessage = Objects.requireNonNullElse(safeMessage, "Agent execution failed");
    }
}
