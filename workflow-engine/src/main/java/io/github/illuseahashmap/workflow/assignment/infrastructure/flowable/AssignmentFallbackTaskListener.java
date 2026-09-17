package io.github.illuseahashmap.workflow.assignment.infrastructure.flowable;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentFallbackAction;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentFallbackCommandRepository;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentTargetType;
import io.github.illuseahashmap.workflow.assignment.domain.NodeAssignmentRule;
import java.util.List;
import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Component;

@Component("assignmentFallbackTaskListener")
public class AssignmentFallbackTaskListener implements TaskListener {

    private final AssignmentRuleService assignmentRuleService;
    private final AssignmentFallbackCommandRepository fallbackCommandRepository;
    private final TaskService taskService;

    public AssignmentFallbackTaskListener(AssignmentRuleService assignmentRuleService,
                                          AssignmentFallbackCommandRepository fallbackCommandRepository,
                                          TaskService taskService) {
        this.assignmentRuleService = assignmentRuleService;
        this.fallbackCommandRepository = fallbackCommandRepository;
        this.taskService = taskService;
    }

    @Override
    public void notify(DelegateTask delegateTask) {
        if (hasHandler(delegateTask)) {
            return;
        }
        NodeAssignmentRule rule = assignmentRuleService.match(
                delegateTask.getTenantId(),
                delegateTask.getProcessDefinitionId(),
                delegateTask.getTaskDefinitionKey(),
                delegateTask.getVariables());
        if (rule == null) {
            return;
        }
        switch (rule.emptyUserStrategy()) {
            case TO_ASSIGNEE -> assignFallback(delegateTask, rule);
            case AUTO_COMPLETE -> enqueue(delegateTask, AssignmentFallbackAction.AUTO_COMPLETE);
            case AUTO_REJECT -> enqueue(delegateTask, AssignmentFallbackAction.AUTO_REJECT);
            default -> throw new IllegalStateException("Unsupported empty user strategy: " + rule.emptyUserStrategy());
        }
    }

    private boolean hasHandler(DelegateTask delegateTask) {
        if (delegateTask.getAssignee() != null && !delegateTask.getAssignee().isBlank()) {
            return true;
        }
        List<IdentityLink> identityLinks = taskService.getIdentityLinksForTask(delegateTask.getId());
        return identityLinks.stream().anyMatch(link -> "candidate".equals(link.getType()));
    }

    private void assignFallback(DelegateTask delegateTask, NodeAssignmentRule rule) {
        List<String> fallbackAssignees = rule.targetValues(AssignmentTargetType.FALLBACK_ASSIGNEE);
        if (fallbackAssignees.size() != 1) {
            throw new IllegalStateException("TO_ASSIGNEE requires exactly one fallback assignee");
        }
        delegateTask.setAssignee(fallbackAssignees.getFirst());
    }

    private void enqueue(DelegateTask delegateTask, AssignmentFallbackAction action) {
        fallbackCommandRepository.enqueue(
                delegateTask.getTenantId(),
                delegateTask.getId(),
                delegateTask.getProcessInstanceId(),
                action);
    }
}
