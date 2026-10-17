package io.github.illuseahashmap.workflow.knowledge;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** LangChain4j implementation; its types remain outside knowledge-engine. */
public final class LangChain4jEmbeddingModelAdapter implements EmbeddingModelPort {
    private final EmbeddingModel model;
    private final String modelVersion;
    private final int dimension;
    private final int batchSize;

    public LangChain4jEmbeddingModelAdapter(EmbeddingModel model, String modelVersion,
                                             int dimension, int batchSize) {
        this.model = Objects.requireNonNull(model, "model");
        this.modelVersion = Objects.requireNonNull(modelVersion, "modelVersion");
        if (dimension < 1 || dimension > 4096 || batchSize < 1 || batchSize > 128) {
            throw new IllegalArgumentException("Embedding dimension or batch size is out of range");
        }
        this.dimension = dimension;
        this.batchSize = batchSize;
    }

    @Override
    public Batch embed(List<String> texts) {
        Objects.requireNonNull(texts, "texts");
        List<List<Float>> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            var segments = texts.subList(start, Math.min(start + batchSize, texts.size())).stream()
                    .map(TextSegment::from).toList();
            var response = model.embedAll(segments).content();
            if (response == null || response.size() != segments.size()) {
                throw new IllegalStateException("Embedding provider returned an incomplete batch");
            }
            response.forEach(embedding -> vectors.add(embedding.vectorAsList()));
        }
        return new Batch(modelVersion, dimension, vectors);
    }
}
