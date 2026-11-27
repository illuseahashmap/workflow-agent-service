package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;
import io.github.illuseahashmap.knowledge.ingestion.domain.Chunk;
import io.github.illuseahashmap.knowledge.retrieval.application.port.ChunkSearchPort;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceType;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordRetrieverTest {
    private static final DocumentVersion VERSION = new DocumentVersion(
            1L, "tenant-a", "policies", "leave.md", 3, "hash", "READY", Instant.EPOCH);

    @Test
    void mapsSearchHitsToVersionedEvidenceAndCitations() {
        Chunk chunk = Chunk.of(VERSION, 0, "Annual leave is approved by the manager.");
        KeywordRetriever retriever = new KeywordRetriever((tenant, request, limit) -> {
            assertThat(tenant).isEqualTo("tenant-a");
            assertThat(limit).isEqualTo(5);
            return List.of(new ChunkSearchPort.SearchHit(chunk, 0.87));
        });

        var result = retriever.retrieve("tenant-a", request());

        assertThat(result.status()).isEqualTo(RetrievalStatus.SUCCESS);
        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().get(0).reference().sourceVersion()).isEqualTo("3");
        assertThat(result.citations()).hasSize(1);
    }

    @Test
    void returnsExplicitEmptyWhenStorageHasNoHits() {
        var result = new KeywordRetriever((tenant, request, limit) -> List.of())
                .retrieve("tenant-a", request());

        assertThat(result.status()).isEqualTo(RetrievalStatus.EMPTY);
        assertThat(result.abstained()).isTrue();
    }

    @Test
    void rejectsEvidenceContractItCannotProduce() {
        var request = new RetrievalRequest("approval", List.of(), List.of(), null, 5,
                RetrievalRequest.StrategyHint.KEYWORD, 0, List.of(EvidenceType.RELATION_PATH));

        var result = new KeywordRetriever((tenant, ignored, limit) -> {
            throw new AssertionError("search must not be called");
        }).retrieve("tenant-a", request);

        assertThat(result.status()).isEqualTo(RetrievalStatus.REJECTED);
        assertThat(result.abstained()).isTrue();
    }

    private RetrievalRequest request() {
        return new RetrievalRequest("approval", List.of("policies"), List.of(), null, 5,
                RetrievalRequest.StrategyHint.KEYWORD, 0, List.of(EvidenceType.CHUNK));
    }
}
