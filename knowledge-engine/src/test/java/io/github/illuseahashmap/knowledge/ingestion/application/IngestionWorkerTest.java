package io.github.illuseahashmap.knowledge.ingestion.application;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;
import io.github.illuseahashmap.knowledge.catalog.domain.IndexVersion;
import io.github.illuseahashmap.knowledge.ingestion.application.port.IngestionJobRepository;
import io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelException;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeDocumentStore;
import io.github.illuseahashmap.knowledge.ingestion.application.port.KnowledgeIndexPort;
import io.github.illuseahashmap.knowledge.ingestion.domain.DocumentSnapshot;
import io.github.illuseahashmap.knowledge.ingestion.domain.IngestionJob;
import io.github.illuseahashmap.knowledge.ingestion.domain.IngestionStatus;
import io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionWorkerTest {
    private static final Instant NOW = Instant.parse("2026-09-05T01:00:00Z");

    @Test
    void supersededDocumentTerminatesWithoutEmbeddingOrIndexRetry() {
        var jobs = new RecordingJobs(job(5, 1));
        var newer = new DocumentVersion(2, "tenant-a", "policies", "leave.md", 2, "new", "READY", NOW);
        KnowledgeDocumentStore store = (tenant, source, id) -> java.util.List.of(new DocumentSnapshot(newer, "new policy"));
        var service = new DocumentIngestionService(store, (document, chunks) -> {
            throw new AssertionError("Superseded job must not build an index");
        }, new TextChunker(128, 16));
        var worker = new IngestionWorker(jobs, service, 3, Duration.ofSeconds(5), heartbeat());
        worker.runOnce("tenant-a", NOW, NOW.plusSeconds(60));
        assertThat(jobs.saved.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(jobs.saved.errorCode()).isEqualTo("DOCUMENT_VERSION_SUPERSEDED");
    }

    @Test
    void activatesCandidateBeforeMarkingJobSucceeded() {
        IngestionJob job = job(1, 1);
        RecordingJobs jobs = new RecordingJobs(job);
        DocumentIngestionService service = service((document, chunks) -> indexVersion());
        IngestionWorker worker = new IngestionWorker(jobs, service, 3, Duration.ofSeconds(5), heartbeat());

        assertThat(worker.runOnce("tenant-a", NOW, NOW.plusSeconds(60))).isTrue();
        assertThat(jobs.saved.status()).isEqualTo(IngestionStatus.SUCCEEDED);
    }

    @Test
    void retriesWithBoundedStableFailureCodeAndStopsAfterMaxAttempts() {
        IngestionJob job = job(1, 2);
        RecordingJobs jobs = new RecordingJobs(job);
        DocumentIngestionService service = service((document, chunks) -> {
            throw new IllegalStateException("remote parser returned an unexpectedly large response");
        });
        IngestionWorker worker = new IngestionWorker(jobs, service, 2, Duration.ofSeconds(5), heartbeat());

        worker.runOnce("tenant-a", NOW, NOW.plusSeconds(60));

        assertThat(jobs.saved.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(jobs.saved.errorCode()).isEqualTo("INGESTION_FAILED");
    }

    @Test
    void doesNotRetryPermanentEmbeddingFailure() {
        RecordingJobs jobs = new RecordingJobs(job(2, 1));
        DocumentIngestionService service = service((document, chunks) -> {
            throw new EmbeddingModelException("ARK_EMBEDDING_AUTHENTICATION", false);
        });
        IngestionWorker worker = new IngestionWorker(jobs, service, 3, Duration.ofSeconds(5), heartbeat());

        worker.runOnce("tenant-a", NOW, NOW.plusSeconds(60));

        assertThat(jobs.saved.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(jobs.saved.errorCode()).isEqualTo("ARK_EMBEDDING_AUTHENTICATION");
    }

    @Test
    void retriesTransientEmbeddingFailure() {
        RecordingJobs jobs = new RecordingJobs(job(3, 1));
        DocumentIngestionService service = service((document, chunks) -> {
            throw new EmbeddingModelException("ARK_EMBEDDING_TIMEOUT", true);
        });
        IngestionWorker worker = new IngestionWorker(jobs, service, 3, Duration.ofSeconds(5), heartbeat());

        worker.runOnce("tenant-a", NOW, NOW.plusSeconds(60));

        assertThat(jobs.saved.status()).isEqualTo(IngestionStatus.QUEUED);
        assertThat(jobs.saved.errorCode()).isEqualTo("ARK_EMBEDDING_TIMEOUT");
    }

    private DocumentIngestionService service(KnowledgeIndexPort indexPort) {
        DocumentVersion version = new DocumentVersion(1, "tenant-a", "policies", "leave.md", 1,
                "hash", "READY", NOW);
        KnowledgeDocumentStore documents = (tenant, source, hash) ->
                java.util.List.of(new DocumentSnapshot(version, "annual leave policy"));
        return new DocumentIngestionService(documents, indexPort, new TextChunker(128, 16));
    }

    private IndexVersion indexVersion() {
        return new IndexVersion(2, "tenant-a", "policies", 1, "none", "CANDIDATE", NOW);
    }

    private IngestionJob job(long id, int attempt) {
        return new IngestionJob(id, "tenant-a", "policies", "hash", IngestionStatus.RUNNING,
                attempt, NOW, NOW.plusSeconds(60), null, 1L, "worker-test");
    }

    private static final class RecordingJobs implements IngestionJobRepository {
        private final IngestionJob claimed;
        private IngestionJob saved;

        private RecordingJobs(IngestionJob claimed) {
            this.claimed = claimed;
        }

        @Override
        public Optional<IngestionJob> claimNext(String tenantCode, Instant now, Instant leaseUntil) {
            return Optional.of(claimed);
        }

        @Override
        public boolean save(IngestionJob job) {
            saved = job;
            return true;
        }

        @Override
        public boolean complete(IngestionJob job, IndexVersion index) {
            saved = job.succeeded();
            return true;
        }

        @Override
        public boolean renew(IngestionJob job, Instant until) {
            return true;
        }
    }

    private java.util.concurrent.ScheduledExecutorService heartbeat() {
        var scheduler = org.mockito.Mockito.mock(java.util.concurrent.ScheduledExecutorService.class);
        org.mockito.Mockito.doReturn(org.mockito.Mockito.mock(java.util.concurrent.ScheduledFuture.class))
                .when(scheduler).scheduleAtFixedRate(org.mockito.ArgumentMatchers.any(Runnable.class),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any());
        return scheduler;
    }
}
