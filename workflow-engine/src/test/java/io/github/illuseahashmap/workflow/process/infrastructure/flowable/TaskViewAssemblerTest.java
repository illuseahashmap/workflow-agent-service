package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;

class TaskViewAssemblerTest {

    private final TaskService taskService = mock(TaskService.class);
    private final TaskViewAssembler assembler = new TaskViewAssembler(taskService);

    @Test
    void onlyAuthenticatedAssigneeCanOperateAssignedTask() {
        Task task = mock(Task.class);
        when(task.getAssignee()).thenReturn("alice");

        assertThat(assembler.canOperate(task, "alice")).isTrue();
        assertThat(assembler.canOperate(task, "mallory")).isFalse();
    }

    @Test
    void candidateUserCanOperateUnassignedTaskButCandidateGroupIsIgnored() {
        Task task = mock(Task.class);
        IdentityLink candidateUser = mock(IdentityLink.class);
        IdentityLink candidateGroup = mock(IdentityLink.class);
        when(task.getId()).thenReturn("task-1");
        when(candidateUser.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(candidateUser.getUserId()).thenReturn("alice");
        when(candidateGroup.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(candidateGroup.getGroupId()).thenReturn("managers");
        when(taskService.getIdentityLinksForTask("task-1"))
                .thenReturn(List.of(candidateUser, candidateGroup));

        assertThat(assembler.canOperate(task, "alice")).isTrue();
        assertThat(assembler.canOperate(task, "managers")).isFalse();
    }
}
