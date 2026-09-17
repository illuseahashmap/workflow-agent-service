package io.github.illuseahashmap.knowledge.ingestion.application;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;
import io.github.illuseahashmap.knowledge.catalog.domain.IndexVersion;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeDocumentStore;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeIndexPort;
import io.github.illuseahashmap.knowledge.ingestion.domain.DocumentSnapshot;
import io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentIngestionServiceTest {
    @Test
    void readsContentChunksItAndBuildsCandidateIndex() {
        DocumentVersion version = new DocumentVersion(
                1L, "tenant-a", "policies", "leave.md", 1, "hash", "READY", Instant.EPOCH);
        KnowledgeDocumentStore documents = (tenant, source, hash) ->
                new DocumentSnapshot(version, "annual leave policy");
        KnowledgeIndexPort index = (document, chunks) -> {
            assertThat(document).isEqualTo(version);
            assertThat(chunks).hasSize(1);
            return new IndexVersion(2L, "tenant-a", "policies", 2, "none", "CANDIDATE", Instant.EPOCH);
        };

        IngestionResult result = new DocumentIngestionService(
                documents, index, new TextChunker(128, 16)).ingest("tenant-a", "policies", "hash");

        assertThat(result.indexVersion().status()).isEqualTo("CANDIDATE");
        assertThat(result.chunks()).extracting(chunk -> chunk.text()).containsExactly("annual leave policy");
    }
}
