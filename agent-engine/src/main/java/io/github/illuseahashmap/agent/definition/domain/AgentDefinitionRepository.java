package io.github.illuseahashmap.agent.definition.domain;

import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import java.util.Optional;

public interface AgentDefinitionRepository {

    PageSlice<AgentDefinition> page(PageCriteria criteria);

    Optional<AgentDefinition> findById(String tenantCode, long id);

    boolean existsByCode(String tenantCode, String code, Long excludedId);

    AgentDefinition save(AgentDefinition definition);

    void update(AgentDefinition definition);

    /** Deletes a definition only while it has no published version or run history. */
    boolean deleteIfUnused(String tenantCode, long id);

    record PageCriteria(int pageNum, int pageSize, String tenantCode, String keyword, Boolean enabled) {
    }
}
