package io.github.illuseahashmap.knowledge.retrieval.domain;

/** Outcome of a governed knowledge retrieval operation. */
public enum RetrievalStatus {
    SUCCESS,
    EMPTY,
    PARTIAL,
    REJECTED,
    FAILED
}
