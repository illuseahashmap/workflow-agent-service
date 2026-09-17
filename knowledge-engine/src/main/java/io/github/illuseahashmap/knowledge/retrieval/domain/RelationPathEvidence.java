package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.List;
import java.util.Objects;

/** Evidence represented by an ordered relation path rather than a text chunk. */
public record RelationPathEvidence(
        EvidenceReference reference,
        List<EvidenceReference> path,
        double score
) implements Evidence {

    public RelationPathEvidence {
        reference = Objects.requireNonNull(reference, "reference must not be null");
        path = List.copyOf(Objects.requireNonNull(path, "path must not be null"));
        if (path.isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }
        if (!Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException("score must be finite and non-negative");
        }
    }

    @Override
    public EvidenceType type() {
        return EvidenceType.RELATION_PATH;
    }
}
