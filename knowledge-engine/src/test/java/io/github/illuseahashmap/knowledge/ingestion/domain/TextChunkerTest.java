package io.github.illuseahashmap.knowledge.ingestion.domain;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {
    private static final DocumentVersion VERSION = new DocumentVersion(
            1L, "tenant-a", "policies", "leave.md", 1, "document-hash", "READY", Instant.EPOCH);

    @Test
    void normalizesMarkdownAndUsesStableChunkIdentity() {
        DocumentSnapshot snapshot = new DocumentSnapshot(VERSION, "alpha\r\n\r\nbeta\r\ngamma");
        TextChunker chunker = new TextChunker(128, 16);

        var first = chunker.chunk(snapshot);
        var second = chunker.chunk(snapshot);

        assertThat(first).hasSize(1);
        assertThat(first.get(0).text()).isEqualTo("alpha\n\nbeta\ngamma");
        assertThat(first).isEqualTo(second);
        assertThat(first.get(0).documentVersion()).isEqualTo(VERSION);
    }

    @Test
    void splitsLongTextWithBoundedChunkSizeAndOrderedOrdinals() {
        String content = "a".repeat(140) + "\n" + "b".repeat(140);
        var chunks = new TextChunker(128, 16).chunk(new DocumentSnapshot(VERSION, content));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text().length()).isLessThanOrEqualTo(128));
        assertThat(chunks).extracting(Chunk::ordinal).containsExactly(0, 1, 2);
    }
}
