package io.github.illuseahashmap.knowledge.ingestion.application;

import io.github.illuseahashmap.knowledge.ingestion.application.port.IngestionJobRepository;
import io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelException;
import io.github.illuseahashmap.knowledge.ingestion.domain.IngestionJob;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** One bounded, lease-based ingestion attempt; scheduling remains outside the knowledge module. */
public final class IngestionWorker {
    private static final String FAILURE_CODE = "INGESTION_FAILED";
    private final IngestionJobRepository jobRepository;
    private final DocumentIngestionService ingestionService;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final java.util.concurrent.ScheduledExecutorService heartbeat;

    public IngestionWorker(
            IngestionJobRepository jobRepository,
            DocumentIngestionService ingestionService,
            int maxAttempts,
            Duration retryDelay,
            java.util.concurrent.ScheduledExecutorService heartbeat
    ) {
        this.jobRepository = Objects.requireNonNull(jobRepository, "jobRepository must not be null");
        this.ingestionService = Objects.requireNonNull(ingestionService, "ingestionService must not be null");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay must not be null");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
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
        Duration lease = Duration.between(now, leaseUntil);
        var leaseLost = new java.util.concurrent.atomic.AtomicBoolean();
        var renewal = heartbeat.scheduleAtFixedRate(
                () -> {
                    try {
                        if (!jobRepository.renew(job, Instant.now().plus(lease))) {
                            leaseLost.set(true);
                        }
                    } catch (RuntimeException exception) {
                        leaseLost.set(true);
                    }
                },
                Math.max(1, lease.toMillis() / 3), Math.max(1, lease.toMillis() / 3),
                java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            IngestionResult result = ingestionService.ingest(job.tenantCode(), job.sourceCode(), job.documentVersionId());
            if (!leaseLost.get()) {
                jobRepository.complete(job, result.indexVersion());
            }
        } catch (RuntimeException exception) {
            if (leaseLost.get()) {
                return true;
            }
            String code = exception instanceof EmbeddingModelException embedding
                    ? embedding.errorCode() : exception instanceof io.github.illuseahashmap.knowledge.ingestion.domain.DocumentSupersededException
                    ? "DOCUMENT_VERSION_SUPERSEDED" : FAILURE_CODE;
            boolean retryable = !(exception instanceof io.github.illuseahashmap.knowledge.ingestion.domain.DocumentSupersededException)
                    && (!(exception instanceof EmbeddingModelException embedding) || embedding.retryable());
            if (!retryable || job.attempt() >= maxAttempts) {
                jobRepository.save(job.failed(code));
            } else {
                jobRepository.save(job.retryAt(Instant.now().plus(retryDelay), code));
            }
        } finally {
            renewal.cancel(false);
        }
        return true;
    }
}
