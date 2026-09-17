package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.knowledge.ingestion.application.DocumentIngestionService;
import io.github.illuseahashmap.knowledge.ingestion.application.IngestionWorker;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeDocumentStore;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeIndexLifecyclePort;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeIndexPort;
import io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker;
import io.github.illuseahashmap.knowledge.retrieval.application.KnowledgeRetrievalService;
import io.github.illuseahashmap.knowledge.retrieval.application.KeywordRetriever;
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

import java.time.Duration;

/** Opt-in composition for ingestion; disabled until a deployment explicitly enables knowledge ingestion. */
@Configuration
public class KnowledgeIngestionConfiguration {
    @Bean
    @ConditionalOnProperty(name = "workflow.knowledge.retrieval.enabled", havingValue = "true")
    public KeywordRetriever keywordRetriever(ChunkSearchPort chunkSearchPort) {
        return new KeywordRetriever(chunkSearchPort);
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
            KnowledgeIndexPort indexPort
    ) {
        return new DocumentIngestionService(documentStore, indexPort, new TextChunker(1200, 120));
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
