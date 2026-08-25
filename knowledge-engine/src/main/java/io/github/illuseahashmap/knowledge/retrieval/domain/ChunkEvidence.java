package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.Objects;

/** First-slice evidence produced by keyword/vector/hybrid retrievers. */
public record ChunkEvidence(
        EvidenceReference reference,
        String text,
        double score,
        String title
) {
    public ChunkEvidence {
        reference = Objects.requireNonNull(reference, "reference must not be null");
        text = Objects.requireNonNull(text, "text must not be null");
        title = Objects.requireNonNullElse(title, "");
        if (!Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException("score must be finite and non-negative");
        }
    }
}
