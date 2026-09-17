package io.github.illuseahashmap.workflow.process.application.port;

import java.util.Optional;

/** Workflow-owned view of an immutable published Agent version. */
public interface AgentVersionCatalog {

    Optional<PublishedAgentVersion> findPublished(String tenantCode, long versionId);

    record PublishedAgentVersion(
            long id,
            String executionMode,
            int agentRunTimeoutSeconds,
            String inputSchemaJson,
            String outputSchemaJson
    ) {
    }
}
