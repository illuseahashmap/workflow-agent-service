package io.github.illuseahashmap.agent.runtime.domain;

/** Stable failure taxonomy used to select a recovery action. */
public enum AgentFailureCategory {
    PROVIDER_TRANSIENT,
    PROVIDER_PERMANENT,
    OUTPUT_CONTRACT,
    TOOL_PROTOCOL,
    INPUT_CONTRACT,
    CONFIGURATION,
    RESULT_POLICY,
    BUSINESS_REJECTION,
    EXECUTION_UNEXPECTED,
    DEADLINE;
}
