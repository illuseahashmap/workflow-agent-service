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
public class KnowledgeIngestionSchedulingConfig implements AutoCloseable {
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(4);
    private final java.util.Set<String> activeTenants = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicInteger cursor = new java.util.concurrent.atomic.AtomicInteger();
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
            var tenants = tenantRepository.findEnabled();
            for (int offset = 0; offset < tenants.size(); offset++) {
                var tenant = tenants.get(Math.floorMod(cursor.get() + offset, tenants.size()));
                if (!activeTenants.add(tenant.tenantCode())) {
                    continue;
                }
                if (!capacity.tryAcquire()) {
                    activeTenants.remove(tenant.tenantCode());
                    break;
                }
                try {
                    executor.submit(() -> {
                        try {
                            TrustedDataAccessContext.runAsSystemWorker(() -> {
                                for (int index = 0; index < batchSize; index++) {
                                    Instant now = Instant.now();
                                    if (!ingestionWorker.runOnce(tenant.tenantCode(), now, now.plus(leaseDuration))) {
                                        break;
                                    }
                                }
                            });
                        } finally {
                            capacity.release();
                            activeTenants.remove(tenant.tenantCode());
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException exception) {
                    capacity.release();
                    activeTenants.remove(tenant.tenantCode());
                    throw exception;
                }
            }
            cursor.incrementAndGet();
        });
    }

    @Override
    public void close() {
        executor.close();
    }
}
