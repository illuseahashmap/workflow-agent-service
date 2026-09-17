package io.github.illuseahashmap.knowledge.ingestion.application.port;

import io.github.illuseahashmap.knowledge.ingestion.domain.DocumentSnapshot;

/** Reads normalized document snapshots without leaking storage-specific content models. */
public interface KnowledgeDocumentStore {
    DocumentSnapshot loadSnapshot(String tenantCode, String sourceCode, String documentHash);
}
