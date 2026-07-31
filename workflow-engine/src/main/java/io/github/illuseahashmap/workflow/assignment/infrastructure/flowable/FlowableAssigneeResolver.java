package io.github.illuseahashmap.workflow.assignment.infrastructure.flowable;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.application.port.PersonnelResolver;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentTargetType;
import io.github.illuseahashmap.workflow.assignment.domain.NodeAssignmentRule;
import java.util.Collection;
import java.util.List;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component("assigneeService")
public class FlowableAssigneeResolver {

    private static final String TENANT_VARIABLE = "tenantId";
    private static final String ASSIGNEE_SUFFIX = "_assignee";
    private static final String ASSIGNEE_LIST_SUFFIX = "_assigneeList";
    private static final String CANDIDATE_GROUP_LIST_SUFFIX = "_candidateGroupList";

    private final AssignmentRuleService assignmentRuleService;
    private final ObjectProvider<PersonnelResolver> personnelResolverProvider;

    public FlowableAssigneeResolver(AssignmentRuleService assignmentRuleService,
                                    ObjectProvider<PersonnelResolver> personnelResolverProvider) {
        this.assignmentRuleService = assignmentRuleService;
        this.personnelResolverProvider = personnelResolverProvider;
    }

    public String getAssignee(DelegateExecution execution) {
        String taskKey = execution.getCurrentActivityId();
        Object configured = execution.getVariable(taskKey + ASSIGNEE_SUFFIX);
        if (configured != null) {
            return configured.toString();
        }
        List<String> personnel = resolvePersonnel(execution, taskKey);
        if (!personnel.isEmpty()) {
            return personnel.getFirst();
        }
        NodeAssignmentRule rule = matchRule(execution, taskKey);
        if (rule == null) {
            return null;
        }
        List<String> assignees = rule.targetValues(AssignmentTargetType.ASSIGNEE);
        if (!assignees.isEmpty()) {
            return assignees.getFirst();
        }
        return rule.targetValues(AssignmentTargetType.FALLBACK_ASSIGNEE).stream().findFirst().orElse(null);
    }

    public String getCandidates(DelegateExecution execution) {
        String taskKey = execution.getCurrentActivityId();
        Object configured = execution.getVariable(taskKey + ASSIGNEE_LIST_SUFFIX);
        if (configured != null) {
            return join(configured);
        }
        List<String> personnel = resolvePersonnel(execution, taskKey);
        if (!personnel.isEmpty()) {
            return String.join(",", personnel);
        }
        NodeAssignmentRule rule = matchRule(execution, taskKey);
        return rule == null ? null : String.join(",", rule.targetValues(AssignmentTargetType.CANDIDATE_USER));
    }

    public String getCandidateGroups(DelegateExecution execution) {
        String taskKey = execution.getCurrentActivityId();
        Object configured = firstNonNull(
                execution.getVariable(taskKey + CANDIDATE_GROUP_LIST_SUFFIX),
                execution.getVariable("candidateGroupList"));
        if (configured != null) {
            return join(configured);
        }
        NodeAssignmentRule rule = matchRule(execution, taskKey);
        return rule == null ? null : String.join(",", rule.targetValues(AssignmentTargetType.CANDIDATE_GROUP));
    }

    public List<String> getAssigneeList(DelegateExecution execution) {
        String taskKey = execution.getCurrentActivityId();
        Object configured = firstNonNull(
                execution.getVariable(taskKey + ASSIGNEE_LIST_SUFFIX),
                execution.getVariable("assigneeList"));
        if (configured instanceof Collection<?> collection) {
            return collection.stream().map(Object::toString).filter(StringUtils::hasText).toList();
        }
        List<String> personnel = resolvePersonnel(execution, taskKey);
        if (!personnel.isEmpty()) {
            return personnel;
        }
        NodeAssignmentRule rule = matchRule(execution, taskKey);
        return rule == null ? List.of() : rule.targetValues(AssignmentTargetType.COUNTERSIGN_USER);
    }

    private NodeAssignmentRule matchRule(DelegateExecution execution, String taskKey) {
        Object tenantValue = execution.getVariable(TENANT_VARIABLE);
        if (tenantValue == null || !StringUtils.hasText(execution.getProcessDefinitionId())) {
            return null;
        }
        return assignmentRuleService.match(
                tenantValue.toString(), execution.getProcessDefinitionId(), taskKey, execution.getVariables());
    }

    private List<String> resolvePersonnel(DelegateExecution execution, String taskKey) {
        PersonnelResolver resolver = personnelResolverProvider.getIfAvailable();
        if (resolver == null) {
            return List.of();
        }
        try {
            return normalize(resolver.resolve(
                    processDefinitionKey(execution.getProcessDefinitionId()),
                    taskKey,
                    execution.getProcessInstanceBusinessKey(),
                    execution.getVariables()));
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private String processDefinitionKey(String processDefinitionId) {
        int separator = processDefinitionId == null ? -1 : processDefinitionId.indexOf(':');
        return separator < 0 ? processDefinitionId : processDefinitionId.substring(0, separator);
    }

    private List<String> normalize(List<String> values) {
        return values == null ? List.of()
                : values.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
    }

    private String join(Object value) {
        if (value instanceof Collection<?> collection) {
            return String.join(",", collection.stream().map(Object::toString).toList());
        }
        return value.toString();
    }

    private Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }
}
