package io.github.illuseahashmap.knowledge.ingestion.application;

import io.github.illuseahashmap.knowledge.ingestion.application.port.IngestionJobRepository;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeIndexLifecyclePort;
import io.github.illuseahashmap.knowledge.ingestion.domain.IngestionJob;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** One bounded, lease-based ingestion attempt; scheduling remains outside the knowledge module. */
public final class IngestionWorker {
    private static final String FAILURE_CODE = "INGESTION_FAILED";
    private final IngestionJobRepository jobRepository;
    private final DocumentIngestionService ingestionService;
    private final KnowledgeIndexLifecyclePort indexLifecycle;
    private final int maxAttempts;
    private final Duration retryDelay;

    public IngestionWorker(
            IngestionJobRepository jobRepository,
            DocumentIngestionService ingestionService,
            KnowledgeIndexLifecyclePort indexLifecycle,
            int maxAttempts,
            Duration retryDelay
    ) {
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.ingestionService = Objects.requireNonNull(ingestionService, "ingestionService must not be null");
        this.indexLifecycle = Objects.requireNonNull(indexLifecycle, "indexLifecycle must not be null");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay must not be null");
        if (retryDelay.isNegative() || retryDelay.isZero()) {
            throw new IllegalArgumentException("retryDelay must be positive");
        }
    }

    public boolean runOnce(String tenantCode, Instant now, Instant leaseUntil) {
        var claimed = jobRepository.claimNext(tenantCode, now, leaseUntil);
        if (claimed.isEmpty()) {
            return false;
        }
        IngestionJob job = claimed.get();
        try {
            IngestionResult result = ingestionService.ingest(job.tenantCode(), job.sourceCode(), job.documentHash());
            indexLifecycle.activate(result.indexVersion());
            jobRepository.save(job.succeeded());
        } catch (RuntimeException exception) {
            if (job.attempt() >= maxAttempts) {
                jobRepository.save(job.failed(FAILURE_CODE));
            } else {
                jobRepository.save(job.retryAt(now.plus(retryDelay), FAILURE_CODE));
            }
        }
        return true;
    }
}
