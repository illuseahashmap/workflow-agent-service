package io.github.illuseahashmap.agent.definition.application;

import io.github.illuseahashmap.agent.definition.application.dto.AgentDefinitionCommand;
import io.github.illuseahashmap.agent.definition.application.dto.AgentDefinitionView;
import io.github.illuseahashmap.agent.definition.application.dto.AgentVersionCommand;
import io.github.illuseahashmap.agent.definition.application.dto.AgentVersionView;
import io.github.illuseahashmap.agent.definition.application.dto.PublishedAgentVersionView;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.List;

public interface AgentDefinitionService {

    PageResult<AgentDefinitionView> page(Integer pageNum, Integer pageSize, String keyword, Boolean enabled);

    PageResult<PublishedAgentVersionView> publishedVersions(
            Integer pageNum, Integer pageSize, String keyword, Long versionId);

    AgentDefinitionView create(AgentDefinitionCommand command);

    AgentDefinitionView update(long id, AgentDefinitionCommand command);

    List<AgentVersionView> versions(long definitionId);

    AgentVersionView createDraft(long definitionId);

    AgentVersionView updateDraft(long definitionId, long versionId, AgentVersionCommand command);

    AgentVersionView publish(long definitionId, long versionId);
}
