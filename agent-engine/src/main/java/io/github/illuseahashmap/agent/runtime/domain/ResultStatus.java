package io.github.illuseahashmap.agent.runtime.domain;

/**
 * Business meaning of an Agent result, independent of execution state.
 */
public enum ResultStatus {

    SUCCESS,
    EMPTY,
    PARTIAL,
    REJECTED,
    FAILED
}
