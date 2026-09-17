package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunView;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunDetailView;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunStatus;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import java.util.List;
import java.util.Optional;

public interface AgentRunQueryRepository {

    PageSlice<AgentRunView> page(PageCriteria criteria);

    Optional<AgentRunDetailView> findDetail(String tenantCode, long runId);

    /** Returns the Agent executions belonging to one tenant-owned workflow instance. */
    List<AgentRunView> findByProcessInstance(String tenantCode, String processInstanceId);

    record PageCriteria(
            int pageNum,
            int pageSize,
            String tenantCode,
            String keyword,
            AgentRunStatus status
    ) {
    }
}
