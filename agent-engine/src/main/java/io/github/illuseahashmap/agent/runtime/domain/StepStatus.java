package io.github.illuseahashmap.agent.runtime.domain;

/**
 * Lifecycle states of an auditable execution step.
 */
public enum StepStatus {

    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED
}
