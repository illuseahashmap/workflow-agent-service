package io.github.illuseahashmap.agent.runtime.domain;

/**
 * Lifecycle states of one execution attempt.
 */
public enum AttemptStatus {

    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED
}
