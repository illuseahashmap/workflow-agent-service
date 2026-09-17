package io.github.illuseahashmap.workflow.assignment.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.application.port.PersonnelResolver;
import java.util.List;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class FlowableAssigneeResolverTest {

    @Mock
    private AssignmentRuleService assignmentRuleService;
    @Mock
    private ObjectProvider<PersonnelResolver> personnelResolverProvider;
    @Mock
    private DelegateExecution execution;

    private FlowableAssigneeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new FlowableAssigneeResolver(assignmentRuleService, personnelResolverProvider);
        when(execution.getCurrentActivityId()).thenReturn("review");
    }

    @Test
    void manualAssigneeTakesPriorityOverRule() {
        when(execution.getVariable("review_assignee")).thenReturn("alice");

        assertThat(resolver.getAssignee(execution)).isEqualTo("alice");
        verifyNoInteractions(assignmentRuleService, personnelResolverProvider);
    }

    @Test
    void manualCandidateUsersTakePriorityOverRule() {
        when(execution.getVariable("review_assigneeList"))
                .thenReturn(List.of("alice", "bob"));

        assertThat(resolver.getCandidates(execution)).isEqualTo("alice,bob");
        assertThat(resolver.getAssigneeList(execution)).containsExactly("alice", "bob");
        verifyNoInteractions(assignmentRuleService, personnelResolverProvider);
    }
}
