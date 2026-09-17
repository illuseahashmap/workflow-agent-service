package io.github.illuseahashmap.agent.runtime.domain;

public enum AgentRecoveryAction {
    RETRY_PROVIDER,
    REPAIR_OUTPUT,
    REPAIR_TOOL_CALL,
    WAIT_FOR_REVIEW,
    FIX_CONFIGURATION,
    REJECT_BUSINESS,
    TERMINATE
}
