package io.github.illuseahashmap.knowledge.ingestion.application.port;

import io.github.illuseahashmap.knowledge.ingestion.domain.IngestionJob;
import java.time.Instant;
import java.util.Optional;

/** Storage port for lease-based, resumable ingestion workers. */
public interface IngestionJobRepository {
    Optional<IngestionJob> claimNext(String tenantCode, Instant now, Instant leaseUntil);
    boolean save(IngestionJob job);
    boolean complete(IngestionJob job, io.github.illuseahashmap.knowledge.catalog.domain.IndexVersion index);
    boolean renew(IngestionJob job, Instant leaseUntil);
}
