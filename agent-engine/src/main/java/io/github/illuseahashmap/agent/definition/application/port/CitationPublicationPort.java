package io.github.illuseahashmap.agent.definition.application.port;

import java.util.Set;

/** Verify the full reviewed evidence set against the immutable published retrieval boundary. */
public interface CitationPublicationPort {
    boolean containsAll(String tenantCode, long profileVersionId, Set<String> chunkIds);
}
