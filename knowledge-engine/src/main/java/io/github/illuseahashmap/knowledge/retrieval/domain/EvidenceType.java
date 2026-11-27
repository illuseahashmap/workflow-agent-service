package io.github.illuseahashmap.knowledge.retrieval.domain;

/** Stable evidence categories; storage-specific chunk types do not leak here. */
public enum EvidenceType {
    CHUNK,
    RELATION_PATH,
    STRUCTURED_RECORD,
    EXTERNAL
}
