package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.knowledge.ingestion.application.IngestionWorker;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import io.github.illuseahashmap.workflow.tenant.domain.WorkflowTenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;

/** Application scheduling boundary for tenant-isolated knowledge ingestion. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "workflow.knowledge.ingestion.enabled", havingValue = "true")
public class KnowledgeIngestionSchedulingConfig {
    private final IngestionWorker ingestionWorker;
    private final WorkflowTenantRepository tenantRepository;
    private final int batchSize;
    private final Duration leaseDuration;

    public KnowledgeIngestionSchedulingConfig(
            IngestionWorker ingestionWorker,
            WorkflowTenantRepository tenantRepository,
            @Value("${workflow.knowledge.ingestion.batch-size:5}") int batchSize,
            @Value("${workflow.knowledge.ingestion.lease-seconds:60}") int leaseSeconds
    ) {
        this.ingestionWorker = ingestionWorker;
        this.tenantRepository = tenantRepository;
        this.batchSize = Math.max(1, Math.min(batchSize, 50));
        this.leaseDuration = Duration.ofSeconds(Math.max(10, Math.min(leaseSeconds, 900)));
    }

    @Bean(name = "knowledgeIngestionTaskScheduler")
    public ThreadPoolTaskScheduler knowledgeIngestionTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("knowledge-ingestion-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Scheduled(
            fixedDelayString = "${workflow.knowledge.ingestion.poll-delay-ms:1000}",
            scheduler = "knowledgeIngestionTaskScheduler")
    public void consumeAvailableJobs() {
        TrustedDataAccessContext.runAsSystemWorker(() -> {
            Instant now = Instant.now();
            for (var tenant : tenantRepository.findEnabled()) {
                for (int index = 0; index < batchSize; index++) {
                    if (!ingestionWorker.runOnce(tenant.tenantCode(), now, now.plus(leaseDuration))) {
                        break;
                    }
                }
            }
        });
    }
}
