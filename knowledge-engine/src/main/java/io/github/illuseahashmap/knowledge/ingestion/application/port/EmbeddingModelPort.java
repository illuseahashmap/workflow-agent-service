package io.github.illuseahashmap.knowledge.ingestion.application.port;

import java.util.List;
import java.util.Objects;

/** Model-neutral embedding boundary. Model identity and dimension are part of every batch. */
public interface EmbeddingModelPort {
    Batch embed(List<String> texts);

    /** Asymmetric retrieval models may require a distinct query-side instruction. */
    default Batch embedQuery(List<String> queries) {
        return embed(queries);
    }

    record Batch(String modelVersion, int dimension, List<List<Float>> vectors) {
        public Batch {
            modelVersion = Objects.requireNonNull(modelVersion, "modelVersion").trim();
            if (modelVersion.isEmpty() || dimension < 1 || dimension > 4096) {
                throw new IllegalArgumentException("Invalid embedding model identity or dimension");
            }
            vectors = Objects.requireNonNull(vectors, "vectors").stream().map(List::copyOf).toList();
            for (List<Float> vector : vectors) {
                if (vector.size() != dimension || vector.stream().anyMatch(value ->
                        value == null || !Float.isFinite(value))) {
                    throw new IllegalArgumentException("Embedding dimension or values are invalid");
                }
            }
        }
    }
}
