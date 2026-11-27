package io.github.illuseahashmap.knowledge.ingestion.domain;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Versioned first-slice chunking policy for UTF-8 text and Markdown. */
public final class TextChunker {
    private final int maxCharacters;
    private final int overlapCharacters;

    public TextChunker(int maxCharacters, int overlapCharacters) {
        if (maxCharacters < 128 || maxCharacters > 16_000) {
            throw new IllegalArgumentException("maxCharacters must be between 128 and 16000");
        }
        if (overlapCharacters < 0 || overlapCharacters >= maxCharacters / 2) {
            throw new IllegalArgumentException("overlapCharacters must be less than half of maxCharacters");
        }
        this.maxCharacters = maxCharacters;
        this.overlapCharacters = overlapCharacters;
    }

    public List<Chunk> chunk(DocumentSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        DocumentVersion version = snapshot.documentVersion();
        String normalized = normalize(snapshot.content());
        List<Chunk> chunks = new ArrayList<>();
        int start = 0;
        int ordinal = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + maxCharacters, normalized.length());
            if (end < normalized.length()) {
                end = preferredBoundary(normalized, start, end);
            }
            String text = normalized.substring(start, end).trim();
            if (!text.isBlank()) {
                chunks.add(Chunk.of(version, ordinal++, text));
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - overlapCharacters, start + 1);
        }
        return List.copyOf(chunks);
    }

    private String normalize(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private int preferredBoundary(String content, int start, int end) {
        int paragraph = content.lastIndexOf("\n\n", end);
        if (paragraph > start + maxCharacters / 3) {
            return paragraph + 2;
        }
        int newline = content.lastIndexOf('\n', end);
        if (newline > start + maxCharacters / 2) {
            return newline + 1;
        }
        int space = content.lastIndexOf(' ', end);
        return space > start + maxCharacters / 2 ? space + 1 : end;
    }
}
