package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.Objects;

/** Versioned, provider-neutral reference to the source of evidence. */
public record EvidenceReference(
        EvidenceType sourceType,
        String sourceId,
        String sourceVersion,
        String locator
) {
    public EvidenceReference {
        sourceType = Objects.requireNonNull(sourceType, "sourceType must not be null");
        sourceId = requireText(sourceId, "sourceId");
        sourceVersion = requireText(sourceVersion, "sourceVersion");
        locator = requireText(locator, "locator");
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name + " must not be null").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
