package io.github.illuseahashmap.workflow.assignment.application;

import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleCommand;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritResult;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;
import io.github.illuseahashmap.workflow.assignment.domain.EmptyUserStrategy;
import io.github.illuseahashmap.workflow.assignment.domain.NodeAssignmentRule;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.Map;

public interface AssignmentRuleService {

    PageResult<NodeAssignmentRule> page(Integer pageNum, Integer pageSize,
                                        String processDefinitionKey, String processDefinitionId,
                                        Integer version, String taskDefinitionKey, String variableName,
                                        AssignmentType assignmentType, EmptyUserStrategy emptyUserStrategy);

    NodeAssignmentRule match(String tenantId, String processDefinitionId,
                             String taskDefinitionKey, Map<String, Object> variables);

    NodeAssignmentRule create(AssignmentRuleCommand command);

    void update(long id, AssignmentRuleCommand command);

    void delete(long id);

    AssignmentRuleInheritResult inherit(String processDefinitionId);
}
