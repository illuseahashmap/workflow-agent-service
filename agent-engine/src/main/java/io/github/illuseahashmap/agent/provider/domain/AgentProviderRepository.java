package io.github.illuseahashmap.agent.provider.domain;

import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import java.util.List;
import java.util.Optional;

public interface AgentProviderRepository {

    PageSlice<AgentProvider> page(PageCriteria criteria);

    List<AgentProvider> findEnabled(String tenantCode);

    Optional<AgentProvider> findById(String tenantCode, long id);

    boolean existsByCode(String tenantCode, String code, Long excludedId);

    AgentProvider save(AgentProvider provider);

    void update(AgentProvider provider);

    void saveCredential(String tenantCode, long providerId, String ciphertext, String hint);

    record PageCriteria(int pageNum, int pageSize, String tenantCode, String keyword, Boolean enabled) {
    }
}
