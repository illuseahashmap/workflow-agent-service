package io.github.illuseahashmap.workflow.process.domain;

/** Flow-level policy applied after an Agent run reaches a non-success terminal state. */
public enum AgentProcessFailurePolicy {
    CONTINUE_EMPTY,
    MANUAL_REVIEW,
    HOLD_FOR_OPERATIONS;

    public static AgentProcessFailurePolicy parseCompatible(String value) {
        if (value == null || value.isBlank() || "FAIL_PROCESS".equals(value)) {
            return HOLD_FOR_OPERATIONS;
        }
        return valueOf(value);
    }
}
