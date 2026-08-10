package io.github.illuseahashmap.agent.runtime.application;

import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunDetailView;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentRunView;
import io.github.illuseahashmap.workflow.shared.response.PageResult;

public interface AgentRunQueryService {

    PageResult<AgentRunView> page(
            Integer pageNum,
            Integer pageSize,
            String keyword,
            String status);

    AgentRunDetailView detail(long runId);
}
