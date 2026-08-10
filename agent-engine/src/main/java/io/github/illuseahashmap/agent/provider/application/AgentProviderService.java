package io.github.illuseahashmap.agent.provider.application;

import io.github.illuseahashmap.agent.provider.application.dto.AgentProviderCommand;
import io.github.illuseahashmap.agent.provider.application.dto.AgentProviderView;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.List;

public interface AgentProviderService {

    PageResult<AgentProviderView> page(Integer pageNum, Integer pageSize, String keyword, Boolean enabled);

    List<AgentProviderView> enabled();

    AgentProviderView create(AgentProviderCommand command);

    AgentProviderView update(long id, AgentProviderCommand command);
}
