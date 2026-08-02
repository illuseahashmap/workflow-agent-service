package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.domain.NodeAssignmentRule;
import io.github.illuseahashmap.workflow.process.application.dto.ParticipantAssignment;
import io.github.illuseahashmap.workflow.process.application.dto.ParticipantRequirementView;
import io.github.illuseahashmap.workflow.process.application.port.ParticipantDirectory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FlowableParticipantAssignmentCoordinatorTest {

    @Mock
    private RepositoryService repositoryService;
    @Mock
    private AssignmentRuleService assignmentRuleService;
    @Mock
    private ParticipantDirectory participantDirectory;

    private FlowableParticipantAssignmentCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new FlowableParticipantAssignmentCoordinator(
                repositoryService, assignmentRuleService, participantDirectory);
    }

    @Test
    void resolvesManualParticipantWithoutExposingTechnicalVariableName() {
        when(repositoryService.getBpmnModel("leave:1:100")).thenReturn(singleTaskModel());
        when(assignmentRuleService.match("tenant-1", "leave:1:100", "managerApproval", Map.of()))
                .thenReturn(null);
        when(participantDirectory.validateSelectableUsernames(List.of("alice")))
                .thenReturn(Set.of("alice"));

        List<ParticipantRequirementView> requirements = coordinator.requirementsForStart(
                "tenant-1", "leave:1:100", Map.of());
        Map<String, Object> variables = coordinator.prepareForStart(
                "tenant-1",
                "leave:1:100",
                Map.of(),
                List.of(new ParticipantAssignment("managerApproval", List.of("alice"))));

        assertThat(requirements).singleElement().satisfies(requirement -> {
            assertThat(requirement.activityId()).isEqualTo("managerApproval");
            assertThat(requirement.multiple()).isFalse();
            assertThat(requirement.required()).isTrue();
        });
        assertThat(variables).containsEntry("managerApproval_assignee", "alice");
    }

    @Test
    void allowsManualParticipantToOverrideConfiguredRuleAtStart() {
        when(repositoryService.getBpmnModel("leave:1:100")).thenReturn(singleTaskModel());
        when(assignmentRuleService.match("tenant-1", "leave:1:100", "managerApproval", Map.of()))
                .thenReturn(mock(NodeAssignmentRule.class));
        when(participantDirectory.validateSelectableUsernames(List.of("alice")))
                .thenReturn(Set.of("alice"));

        List<ParticipantRequirementView> requirements = coordinator.requirementsForStart(
                "tenant-1", "leave:1:100", Map.of());
        Map<String, Object> configuredVariables = coordinator.prepareForStart(
                "tenant-1", "leave:1:100", Map.of(), List.of());
        Map<String, Object> overriddenVariables = coordinator.prepareForStart(
                "tenant-1", "leave:1:100", Map.of(),
                List.of(new ParticipantAssignment("managerApproval", List.of("alice"))));

        assertThat(requirements).singleElement().satisfies(requirement ->
                assertThat(requirement.required()).isFalse());
        assertThat(configuredVariables).isEmpty();
        assertThat(overriddenVariables).containsEntry("managerApproval_assignee", "alice");
    }

    private BpmnModel singleTaskModel() {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("leave");
        StartEvent start = new StartEvent();
        start.setId("start");
        UserTask task = new UserTask();
        task.setId("managerApproval");
        task.setName("主管审批");
        task.setAssignee("${assigneeService.getAssignee(execution)}");
        EndEvent end = new EndEvent();
        end.setId("end");
        SequenceFlow toTask = new SequenceFlow("start", "managerApproval");
        toTask.setId("flow-1");
        toTask.setSourceFlowElement(start);
        toTask.setTargetFlowElement(task);
        SequenceFlow toEnd = new SequenceFlow("managerApproval", "end");
        toEnd.setId("flow-2");
        toEnd.setSourceFlowElement(task);
        toEnd.setTargetFlowElement(end);
        start.setOutgoingFlows(List.of(toTask));
        task.setIncomingFlows(List.of(toTask));
        task.setOutgoingFlows(List.of(toEnd));
        end.setIncomingFlows(List.of(toEnd));
        process.addFlowElement(start);
        process.addFlowElement(task);
        process.addFlowElement(end);
        process.addFlowElement(toTask);
        process.addFlowElement(toEnd);
        model.addProcess(process);
        return model;
    }
}
