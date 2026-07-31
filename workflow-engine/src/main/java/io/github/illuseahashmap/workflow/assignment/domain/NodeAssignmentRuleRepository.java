package io.github.illuseahashmap.workflow.assignment.domain;

import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.List;
import java.util.Optional;

public interface NodeAssignmentRuleRepository {

    PageResult<NodeAssignmentRule> page(RulePageCriteria criteria);

    List<NodeAssignmentRule> findEnabled(String tenantId, String processDefinitionId, String taskDefinitionKey);

    List<NodeAssignmentRule> findByProcessDefinition(String tenantId, String processDefinitionId);

    Optional<NodeAssignmentRule> findById(String tenantId, long id);

    NodeAssignmentRule save(NodeAssignmentRule rule);

    void update(NodeAssignmentRule rule);

    void delete(String tenantId, long id);

    long count(String tenantId, String processDefinitionId);

    record RulePageCriteria(
            int pageNum,
            int pageSize,
            String tenantId,
            String processDefinitionKey,
            String processDefinitionId,
            Integer version,
            String taskDefinitionKey,
            String variableName,
            AssignmentType assignmentType,
            EmptyUserStrategy emptyUserStrategy
    ) {
    }
}
