package io.github.illuseahashmap.workflow.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.provider.infrastructure.openai.ProviderEndpointValidator;
import io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelPort;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Test-only shared vector cache; production ingestion and search still use EmbeddingModelPort. */
final class Rag400EvaluationModels implements EmbeddingModelPort {
    private final ObjectMapper json = new ObjectMapper();
    private final EmbeddingModelPort delegate;
    private final Map<String,List<Float>> cache = new LinkedHashMap<>();
    private final Path path;
    private final String version;
    private final int dimension;
    private final boolean fixture;
    private final Map<String,Object> identity;
    private long requestedTexts, newTexts, embeddingNanos;

    Rag400EvaluationModels(JdbcTemplate jdbc) throws Exception {
        fixture = Boolean.getBoolean("rag400.fixture");
        path = Path.of(System.getProperty("rag400.cache"));
        if (fixture) {
            version = "SYNTHETIC-NOT-QUALITY-v1";
            dimension = 32;
            identity = Map.of("mode", version, "dimension", dimension);
            delegate = null;
        } else {
            var cfg = json.readTree(Path.of(System.getenv("RAG400_EMBEDDING_CONFIG")).toFile());
            version = cfg.required("modelVersion").asText();
            dimension = cfg.required("dimension").asInt();
            var metadata = new LinkedHashMap<String,Object>();
            cfg.fields().forEachRemaining(e -> {
                if (!e.getKey().equals("apiKey")) metadata.put(e.getKey(), json.convertValue(e.getValue(), Object.class));
            });
            identity = metadata;
            delegate = new KnowledgeIngestionConfiguration().embeddingModelPort(jdbc,
                new ProviderEndpointValidator(false, false), json,
                cfg.path("provider").asText("OPENAI_COMPATIBLE"), cfg.required("baseUrl").asText(),
                cfg.required("apiKey").asText(), cfg.required("model").asText(), version, dimension,
                cfg.path("batchSize").asInt(16), cfg.path("timeoutSeconds").asInt(90),
                cfg.path("maxResponseBytes").asInt(8388608),
                cfg.path("queryInstruction").asText(""), cfg.path("corpusInstruction").asText(""));
        }
        if (Files.exists(path)) {
            var saved = json.readTree(path.toFile());
            if (!saved.get("identity").equals(json.valueToTree(identity))) throw new IllegalStateException("Embedding cache identity changed");
            saved.get("vectors").fields().forEachRemaining(e -> {
                var values = new ArrayList<Float>(); e.getValue().forEach(v -> values.add(v.floatValue()));
                cache.put(e.getKey(), new Batch(version, dimension, List.of(values)).vectors().getFirst());
            });
        }
    }

    public Batch embed(List<String> texts) { return apply(texts, false); }
    public Batch embedQuery(List<String> texts) { return apply(texts, true); }
    private String key(String text, boolean query) {
        try {
            return (query ? "q:" : "d:") + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private Batch apply(List<String> texts, boolean query) {
        requestedTexts += texts.size();
        var missing = texts.stream().distinct().filter(t -> !cache.containsKey(key(t, query))).toList();
        if (!missing.isEmpty()) {
            long start = System.nanoTime();
            List<List<Float>> vectors;
            if (fixture) {
                vectors = missing.stream().map(t -> {
                    var vector = new ArrayList<Float>();
                    String hash = key(t, query).substring(2);
                    for (int i=0; i<dimension; i++) vector.add((Integer.parseInt(hash.substring(i*2,i*2+2),16)+1)/256f);
                    return (List<Float>) vector;
                }).toList();
            } else {
                var batch = query ? delegate.embedQuery(missing) : delegate.embed(missing);
                if (!batch.modelVersion().equals(version) || batch.dimension()!=dimension) throw new IllegalStateException("Embedding identity drift");
                vectors = batch.vectors();
            }
            new Batch(version, dimension, vectors);
            for (int i=0; i<missing.size(); i++) cache.put(key(missing.get(i),query), vectors.get(i));
            newTexts += missing.size(); embeddingNanos += System.nanoTime()-start;
            try {
                Files.createDirectories(path.getParent());
                var temp = path.resolveSibling(path.getFileName()+".tmp");
                Files.writeString(temp,json.writeValueAsString(Map.of("identity",identity,"vectors",cache)));
                Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) { throw new IllegalStateException("Cannot persist evaluation vector cache",e); }
        }
        return new Batch(version, dimension, texts.stream().map(t -> cache.get(key(t,query))).toList());
    }
    Map<String,Object> metrics() {
        return Map.of("identity",identity,"fixtureOnly",fixture,"requestedTexts",requestedTexts,
            "newlyEmbeddedTexts",newTexts,"providerPreparationMs",embeddingNanos/1e6,
            "providerTokenUsage","NOT_EXPOSED_BY_PRODUCTION_EMBEDDING_PORT",
            "queryLatencyProtocol","Queries pre-embedded once; reported retrieval latency excludes provider latency");
    }
}
