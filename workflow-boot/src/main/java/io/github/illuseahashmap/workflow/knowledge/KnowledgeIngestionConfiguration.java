package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.ingestion.application.DocumentIngestionService;
import io.github.illuseahashmap.knowledge.ingestion.application.IngestionWorker;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeDocumentStore;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeIndexLifecyclePort;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeIndexPort;
import io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelPort;
import io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker;
import io.github.illuseahashmap.knowledge.retrieval.application.KnowledgeRetrievalService;
import io.github.illuseahashmap.knowledge.retrieval.application.KeywordRetriever;
import io.github.illuseahashmap.knowledge.retrieval.application.StrategyRetriever;
import io.github.illuseahashmap.knowledge.retrieval.application.VectorRetriever;
import io.github.illuseahashmap.knowledge.retrieval.application.port.VectorSearchPort;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeAccessPolicy;
import io.github.illuseahashmap.knowledge.retrieval.application.port.ChunkSearchPort;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetriever;
import io.github.illuseahashmap.knowledge.retrieval.application.port.AgentRetrievalProfileBinding;
import io.github.illuseahashmap.knowledge.retrieval.application.port.RetrievalProfileRepository;
import io.github.illuseahashmap.knowledge.retrieval.application.port.RetrievalTraceRepository;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.DependsOn;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import io.github.illuseahashmap.agent.provider.infrastructure.openai.ProviderEndpointValidator;
import io.github.illuseahashmap.agent.provider.infrastructure.openai.BoundedLangChain4jHttpClientBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.net.http.HttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;

/** Opt-in composition for ingestion; disabled until a deployment explicitly enables knowledge ingestion. */
@Configuration
public class KnowledgeIngestionConfiguration {
    @Bean
    @DependsOn("workflowFlyway")
    @ConditionalOnProperty(name = "workflow.knowledge.vector.enabled", havingValue = "true")
    public EmbeddingModelPort embeddingModelPort(
            JdbcTemplate jdbc,
            ProviderEndpointValidator endpointValidator,
            ObjectMapper mapper,
            @Value("${workflow.knowledge.vector.provider:OPENAI_COMPATIBLE}") String provider,
            @Value("${workflow.knowledge.vector.base-url:}") String baseUrl,
            @Value("${workflow.knowledge.vector.api-key:}") String apiKey,
            @Value("${workflow.knowledge.vector.model:}") String modelName,
            @Value("${workflow.knowledge.vector.model-version:}") String modelVersion,
            @Value("${workflow.knowledge.vector.dimension:0}") int dimension,
            @Value("${workflow.knowledge.vector.batch-size:16}") int batchSize,
            @Value("${workflow.knowledge.vector.timeout-seconds:20}") int timeoutSeconds,
            @Value("${workflow.knowledge.vector.max-response-bytes:2097152}") int maxResponseBytes,
            @Value("${workflow.knowledge.vector.query-instruction:}") String queryInstruction,
            @Value("${workflow.knowledge.vector.corpus-instruction:}") String corpusInstruction) {
        if (baseUrl.isBlank() || apiKey.isBlank() || modelName.isBlank() || modelVersion.isBlank()
                || dimension < 1 || timeoutSeconds < 1) {
            throw new IllegalStateException("Vector retrieval requires an embedding endpoint, key, model, "
                    + "model version, dimension and timeout");
        }
        endpointValidator.validate(URI.create(baseUrl));
        Boolean installed = jdbc.queryForObject("SELECT to_regclass('knowledge_chunk_embedding') IS NOT NULL",
                Boolean.class);
        if (!Boolean.TRUE.equals(installed)) {
            throw new IllegalStateException("Vector retrieval requires the optional pgvector migration");
        }
        if ("VOLCENGINE_ARK".equals(provider)) {
            return new VolcengineArkEmbeddingModelAdapter(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                    .followRedirects(HttpClient.Redirect.NEVER).build(), mapper, URI.create(baseUrl),
                    apiKey, modelName, modelVersion, dimension, Duration.ofSeconds(timeoutSeconds),
                    maxResponseBytes, queryInstruction, corpusInstruction);
        }
        if (!"OPENAI_COMPATIBLE".equals(provider)) {
            throw new IllegalArgumentException("Unsupported embedding provider type");
        }
        var model = OpenAiEmbeddingModel.builder().baseUrl(baseUrl).apiKey(apiKey)
                .modelName(modelName).dimensions(dimension).timeout(Duration.ofSeconds(timeoutSeconds))
                .maxRetries(0).httpClientBuilder(new BoundedLangChain4jHttpClientBuilder(maxResponseBytes)).build();
        return new LangChain4jEmbeddingModelAdapter(model, modelVersion, dimension, batchSize);
    }

    @Bean
    @ConditionalOnProperty(name = "workflow.knowledge.retrieval.enabled", havingValue = "true")
    public KeywordRetriever keywordRetriever(ChunkSearchPort chunkSearchPort) {
        return new KeywordRetriever(chunkSearchPort);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "workflow.knowledge.retrieval.enabled", havingValue = "true")
    public KnowledgeRetriever strategyRetriever(KeywordRetriever keyword,
            ObjectProvider<EmbeddingModelPort> embeddingModel,
            ObjectProvider<VectorSearchPort> vectorSearch) {
        EmbeddingModelPort model = embeddingModel.getIfAvailable();
        VectorSearchPort search = vectorSearch.getIfAvailable();
        return new StrategyRetriever(keyword, model == null || search == null
                ? null : new VectorRetriever(model, search));
    }

    @Bean
    @ConditionalOnProperty(name = "workflow.knowledge.retrieval.enabled", havingValue = "true")
    public KnowledgeRetrievalService knowledgeRetrievalService(
            TenantProvider tenantProvider,
            KnowledgeRetriever retriever,
            KnowledgeAccessPolicy accessPolicy,
            RetrievalTraceRepository traceRepository,
            RetrievalProfileRepository profileRepository,
            AgentRetrievalProfileBinding profileBinding
    ) {
        return new KnowledgeRetrievalService(tenantProvider, retriever, accessPolicy,
                traceRepository, profileRepository, profileBinding);
    }

    @Bean
    @ConditionalOnProperty(name = "workflow.knowledge.ingestion.enabled", havingValue = "true")
    public DocumentIngestionService documentIngestionService(
            KnowledgeDocumentStore documentStore,
            KnowledgeIndexPort indexPort,
            ObjectProvider<EmbeddingModelPort> embeddingModel
    ) {
        return new DocumentIngestionService(documentStore, indexPort, new TextChunker(1200, 120),
                embeddingModel.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "workflow.knowledge.ingestion.enabled", havingValue = "true")
    public IngestionWorker ingestionWorker(
            PostgresIngestionJobRepository jobRepository,
            DocumentIngestionService ingestionService,
            KnowledgeIndexLifecyclePort indexLifecycle
    ) {
        return new IngestionWorker(jobRepository, ingestionService, indexLifecycle,
                5, Duration.ofSeconds(30));
    }
}
