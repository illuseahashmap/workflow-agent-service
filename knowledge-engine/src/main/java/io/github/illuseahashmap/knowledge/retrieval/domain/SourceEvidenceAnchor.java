package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.Objects;

/** Gold/source identity independent of transient chunk IDs and object-storage URLs. */
public record SourceEvidenceAnchor(String documentCode, String documentVersion, String contentHash, EvidenceLocator locator) {
    public SourceEvidenceAnchor {
        if (documentCode == null || documentCode.isBlank() || documentVersion == null || documentVersion.isBlank()
                || contentHash == null || !contentHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("An immutable document identity is required");
        }
        Objects.requireNonNull(locator, "locator");
    }
    public boolean supports(SourceEvidenceAnchor expected, boolean precise) {
        return documentCode.equals(expected.documentCode) && documentVersion.equals(expected.documentVersion)
                && contentHash.equals(expected.contentHash)
                && (precise ? locator.matchesCitation(expected.locator) : locator.covers(expected.locator));
    }
}
