package io.github.illuseahashmap.workflow.assignment.infrastructure.flowable;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentTarget;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentTargetType;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;
import io.github.illuseahashmap.workflow.assignment.domain.EmptyUserStrategy;
import io.github.illuseahashmap.workflow.assignment.domain.NodeAssignmentRule;
import java.util.List;
import java.util.Map;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.service.delegate.DelegateTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssignmentFallbackTaskListenerTest {

    @Mock
    private AssignmentRuleService assignmentRuleService;

    @Mock
    private AssignmentFallbackExecutor fallbackExecutor;

    @Mock
    private TaskService taskService;

    @Mock
    private DelegateTask delegateTask;

    private AssignmentFallbackTaskListener listener;

    @BeforeEach
    void setUp() {
        listener = new AssignmentFallbackTaskListener(assignmentRuleService, fallbackExecutor, taskService);
    }

    @Test
    void shouldNotApplyFallbackWhenCandidateExists() {
        IdentityLink candidate = org.mockito.Mockito.mock(IdentityLink.class);
        when(delegateTask.getId()).thenReturn("task-1");
        when(taskService.getIdentityLinksForTask("task-1")).thenReturn(List.of(candidate));
        when(candidate.getType()).thenReturn("candidate");

        listener.notify(delegateTask);

        verify(assignmentRuleService, never()).match(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldAssignConfiguredFallbackUser() {
        prepareUnassignedTask();
        when(assignmentRuleService.match("tenant-1", "definition-1", "review", Map.of()))
                .thenReturn(rule(EmptyUserStrategy.TO_ASSIGNEE, "fallback-user"));

        listener.notify(delegateTask);

        verify(delegateTask).setAssignee("fallback-user");
    }

    @Test
    void shouldExecuteAutoRejectWhenNoTransactionIsActive() {
        prepareUnassignedTask();
        when(delegateTask.getProcessInstanceId()).thenReturn("instance-1");
        when(assignmentRuleService.match("tenant-1", "definition-1", "review", Map.of()))
                .thenReturn(rule(EmptyUserStrategy.AUTO_REJECT, null));

        listener.notify(delegateTask);

        verify(fallbackExecutor).autoReject("task-1", "instance-1");
    }

    private void prepareUnassignedTask() {
        when(delegateTask.getId()).thenReturn("task-1");
        when(delegateTask.getTenantId()).thenReturn("tenant-1");
        when(delegateTask.getProcessDefinitionId()).thenReturn("definition-1");
        when(delegateTask.getTaskDefinitionKey()).thenReturn("review");
        when(delegateTask.getVariables()).thenReturn(Map.of());
        when(taskService.getIdentityLinksForTask("task-1")).thenReturn(List.of());
    }

    private NodeAssignmentRule rule(EmptyUserStrategy strategy, String fallbackAssignee) {
        List<AssignmentTarget> targets = fallbackAssignee == null
                ? List.of()
                : List.of(new AssignmentTarget(1L, AssignmentTargetType.FALLBACK_ASSIGNEE, fallbackAssignee, 10));
        return new NodeAssignmentRule(
                1L, "tenant-1", "definition-1", "sample", 1, "review", 100,
                AssignmentType.ASSIGNEE, strategy, true, null, List.of(), targets, null, null);
    }
}
