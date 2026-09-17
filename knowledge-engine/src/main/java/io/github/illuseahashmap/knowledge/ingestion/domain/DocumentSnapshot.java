package io.github.illuseahashmap.knowledge.ingestion.domain;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;

import java.util.Objects;

/** Immutable UTF-8 document content paired with the version that owns it. */
public record DocumentSnapshot(DocumentVersion documentVersion, String content) {
    public DocumentSnapshot {
        documentVersion = Objects.requireNonNull(documentVersion, "documentVersion must not be null");
        content = Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
