package io.github.illuseahashmap.knowledge.ingestion.application;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;
import io.github.illuseahashmap.knowledge.catalog.domain.IndexVersion;
import io.github.illuseahashmap.knowledge.ingestion.application.port.IngestionJobRepository;
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
    void activatesCandidateBeforeMarkingJobSucceeded() {
        IngestionJob job = job(1, 1);
        RecordingJobs jobs = new RecordingJobs(job);
        boolean[] activated = {false};
        DocumentIngestionService service = service((document, chunks) -> indexVersion());
        IngestionWorker worker = new IngestionWorker(jobs, service, version -> activated[0] = true,
                3, Duration.ofSeconds(5));

        assertThat(worker.runOnce("tenant-a", NOW, NOW.plusSeconds(60))).isTrue();
        assertThat(activated[0]).isTrue();
        assertThat(jobs.saved.status()).isEqualTo(IngestionStatus.SUCCEEDED);
    }

    @Test
    void retriesWithBoundedStableFailureCodeAndStopsAfterMaxAttempts() {
        IngestionJob job = job(1, 2);
        RecordingJobs jobs = new RecordingJobs(job);
        DocumentIngestionService service = service((document, chunks) -> {
            throw new IllegalStateException("remote parser returned an unexpectedly large response");
        });
        IngestionWorker worker = new IngestionWorker(jobs, service, ignored -> {
            throw new AssertionError("activation must not run");
        }, 2, Duration.ofSeconds(5));

        worker.runOnce("tenant-a", NOW, NOW.plusSeconds(60));

        assertThat(jobs.saved.status()).isEqualTo(IngestionStatus.FAILED);
        assertThat(jobs.saved.errorCode()).isEqualTo("INGESTION_FAILED");
    }

    private DocumentIngestionService service(KnowledgeIndexPort indexPort) {
        DocumentVersion version = new DocumentVersion(1, "tenant-a", "policies", "leave.md", 1,
                "hash", "READY", NOW);
        KnowledgeDocumentStore documents = (tenant, source, hash) ->
                new DocumentSnapshot(version, "annual leave policy");
        return new DocumentIngestionService(documents, indexPort, new TextChunker(128, 16));
    }

    private IndexVersion indexVersion() {
        return new IndexVersion(2, "tenant-a", "policies", 1, "none", "CANDIDATE", NOW);
    }

    private IngestionJob job(long id, int attempt) {
        return new IngestionJob(id, "tenant-a", "policies", "hash", IngestionStatus.RUNNING,
                attempt, NOW, NOW.plusSeconds(60), null);
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
        public void save(IngestionJob job) {
            saved = job;
        }
    }
}
