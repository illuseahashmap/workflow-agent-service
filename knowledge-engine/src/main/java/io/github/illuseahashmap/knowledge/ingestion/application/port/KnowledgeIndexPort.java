package io.github.illuseahashmap.knowledge.ingestion.application.port;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;
import io.github.illuseahashmap.knowledge.catalog.domain.IndexVersion;

/** Provider-neutral indexing port; pgvector belongs behind this boundary. */
public interface KnowledgeIndexPort {
    IndexVersion build(DocumentVersion documentVersion);
}
