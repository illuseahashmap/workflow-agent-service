package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.Map;
import java.util.Objects;

/** Evidence represented by a governed structured record. */
public record StructuredRecordEvidence(
        EvidenceReference reference,
        Map<String, Object> fields,
        double score
) implements Evidence {

    public StructuredRecordEvidence {
        reference = Objects.requireNonNull(reference, "reference must not be null");
        fields = Map.copyOf(Objects.requireNonNull(fields, "fields must not be null"));
        if (!Double.isFinite(score) || score < 0) {
            throw new IllegalArgumentException("score must be finite and non-negative");
        }
    }

    @Override
    public EvidenceType type() {
        return EvidenceType.STRUCTURED_RECORD;
    }
}
