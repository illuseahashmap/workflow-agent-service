package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.Objects;

/** Citation exposed to Agent grounding and audit consumers. */
public record Citation(String citationId, EvidenceReference reference, String excerpt) {
    public Citation {
        citationId = Objects.requireNonNull(citationId, "citationId must not be null");
        reference = Objects.requireNonNull(reference, "reference must not be null");
        excerpt = Objects.requireNonNull(excerpt, "excerpt must not be null");
    }
}
