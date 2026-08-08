package io.github.illuseahashmap.agent.runtime.domain;

/**
 * Retry decision produced by the error classification policy.
 */
public enum AgentFailureDisposition {

    RETRYABLE,
    NON_RETRYABLE,
    RETRIES_EXHAUSTED
}
