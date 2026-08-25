package io.github.illuseahashmap.knowledge.ingestion.application.port;

import io.github.illuseahashmap.knowledge.catalog.domain.DocumentVersion;

/** Reads normalized document snapshots without leaking storage-specific content models. */
public interface KnowledgeDocumentStore {
    DocumentVersion load(String tenantCode, String sourceCode, String documentHash);
}
