package io.github.illuseahashmap.knowledge.ingestion.application;

import io.github.illuseahashmap.knowledge.catalog.domain.IndexVersion;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeDocumentStore;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeIndexPort;
import io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelPort;
import io.github.illuseahashmap.knowledge.ingestion.domain.Chunk;
import io.github.illuseahashmap.knowledge.ingestion.domain.DocumentSnapshot;
import io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker;

import java.util.List;
import java.util.Objects;

/** Application orchestration for candidate index construction; activation stays transactional in infrastructure. */
public final class DocumentIngestionService {
    private final KnowledgeDocumentStore documentStore;
    private final KnowledgeIndexPort indexPort;
    private final TextChunker chunker;
    private final EmbeddingModelPort embeddingModel;

    public DocumentIngestionService(
            KnowledgeDocumentStore documentStore,
            KnowledgeIndexPort indexPort,
            TextChunker chunker
    ) {
        this(documentStore, indexPort, chunker, null);
    }

    public DocumentIngestionService(KnowledgeDocumentStore documentStore, KnowledgeIndexPort indexPort,
                                    TextChunker chunker, EmbeddingModelPort embeddingModel) {
        this.documentStore = Objects.requireNonNull(documentStore, "documentStore must not be null");
        this.indexPort = Objects.requireNonNull(indexPort, "indexPort must not be null");
        this.chunker = Objects.requireNonNull(chunker, "chunker must not be null");
        this.embeddingModel = embeddingModel;
    }

    public IngestionResult ingest(String tenantCode, String sourceCode, String documentHash) {
        DocumentSnapshot snapshot = documentStore.loadSnapshot(tenantCode, sourceCode, documentHash);
        List<Chunk> chunks = chunker.chunk(snapshot);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Document contains no indexable text");
        }
        EmbeddingModelPort.Batch embeddings = embeddingModel == null ? null
                : embeddingModel.embed(chunks.stream().map(Chunk::text).toList());
        if (embeddings != null && embeddings.vectors().size() != chunks.size()) {
            throw new IllegalStateException("Embedding count does not match chunk count");
        }
        IndexVersion indexVersion = indexPort.build(snapshot.documentVersion(), chunks, embeddings);
        return new IngestionResult(indexVersion, chunks);
    }
}
