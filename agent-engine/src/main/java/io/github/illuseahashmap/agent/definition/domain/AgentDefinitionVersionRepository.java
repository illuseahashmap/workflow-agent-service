package io.github.illuseahashmap.agent.definition.domain;

import java.util.List;
import java.util.Optional;

public interface AgentDefinitionVersionRepository {

    List<AgentDefinitionVersion> findByDefinition(String tenantCode, long definitionId);

    Optional<AgentDefinitionVersion> findById(String tenantCode, long definitionId, long versionId);

    Optional<AgentDefinitionVersion> findByVersionId(String tenantCode, long versionId);

    Optional<AgentDefinitionVersion> findPublished(String tenantCode, long definitionId);

    Optional<AgentDefinitionVersion> findLatest(String tenantCode, long definitionId);

    AgentDefinitionVersion save(AgentDefinitionVersion version);

    void updateDraft(AgentDefinitionVersion version);

    void publish(String tenantCode, long definitionId, long versionId, String publishedBy);
}
