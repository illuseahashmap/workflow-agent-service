package io.github.illuseahashmap.knowledge.ingestion.application;

import io.github.illuseahashmap.knowledge.catalog.domain.IndexVersion;
import io.github.illuseahashmap.knowledge.ingestion.domain.Chunk;

import java.util.List;
import java.util.Objects;

/** Result of a deterministic document-to-index candidate build. */
public record IngestionResult(IndexVersion indexVersion, List<Chunk> chunks) {
    public IngestionResult {
        indexVersion = Objects.requireNonNull(indexVersion, "indexVersion must not be null");
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks must not be null"));
    }
}
