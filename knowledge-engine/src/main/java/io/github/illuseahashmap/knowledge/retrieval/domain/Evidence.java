package io.github.illuseahashmap.knowledge.retrieval.domain;

/** Provider-neutral evidence returned by retrieval. */
public interface Evidence {

    EvidenceType type();

    EvidenceReference reference();
}
