package io.github.illuseahashmap.agent.definition.domain;

import java.util.List;
import java.util.Optional;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;

public interface AgentDefinitionVersionRepository {

    List<AgentDefinitionVersion> findByDefinition(String tenantCode, long definitionId);

    Optional<AgentDefinitionVersion> findById(String tenantCode, long definitionId, long versionId);

    Optional<AgentDefinitionVersion> findByVersionId(String tenantCode, long versionId);

    Optional<AgentDefinitionVersion> findPublished(String tenantCode, long definitionId);

    Optional<AgentDefinitionVersion> findLatest(String tenantCode, long definitionId);

    PageSlice<PublishedVersion> pagePublished(PublishedVersionCriteria criteria);

    AgentDefinitionVersion save(AgentDefinitionVersion version);

    void updateDraft(AgentDefinitionVersion version);

    void publish(String tenantCode, long definitionId, long versionId, String publishedBy);

    record PublishedVersionCriteria(
            int pageNum, int pageSize, String tenantCode, String keyword, Long versionId
    ) {
    }

    record PublishedVersion(
            long id, long definitionId, String agentCode, String agentName, int version,
            AgentExecutionMode executionMode, int timeoutSeconds, String inputSchema, String outputSchema
    ) {
    }
}
