package io.github.illuseahashmap.knowledge.ingestion.domain;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;

import java.util.Objects;

/** Immutable UTF-8 document content paired with the version that owns it. */
public record DocumentSnapshot(DocumentVersion documentVersion, String content, java.util.List<String> nativeBlocks) {
    public DocumentSnapshot(DocumentVersion version, String content) { this(version,content,java.util.List.of()); }
    public DocumentSnapshot {
        nativeBlocks = java.util.List.copyOf(nativeBlocks);
        documentVersion = Objects.requireNonNull(documentVersion, "documentVersion must not be null");
        content = Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
