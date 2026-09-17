package io.github.illuseahashmap.workflow.assignment.application;

import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleCommand;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritResult;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleView;
import io.github.illuseahashmap.workflow.assignment.domain.NodeAssignmentRule;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.Map;

public interface AssignmentRuleService {

    PageResult<AssignmentRuleView> page(Integer pageNum, Integer pageSize,
                                        String processDefinitionKey, String processDefinitionId,
                                        Integer version, String taskDefinitionKey, String variableName,
                                        String assignmentType, String emptyUserStrategy);

    NodeAssignmentRule match(String tenantId, String processDefinitionId,
                             String taskDefinitionKey, Map<String, Object> variables);

    AssignmentRuleView create(AssignmentRuleCommand command);

    void update(long id, AssignmentRuleCommand command);

    void delete(long id);

    void deleteByProcessDefinition(String processDefinitionId);

    void deleteByProcessDefinitionKey(String processDefinitionKey);

    AssignmentRuleInheritResult inherit(String processDefinitionId);
}
