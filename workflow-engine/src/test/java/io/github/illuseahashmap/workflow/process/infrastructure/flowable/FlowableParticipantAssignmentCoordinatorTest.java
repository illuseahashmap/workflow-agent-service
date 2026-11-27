package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.Gateway;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.common.engine.impl.el.DefaultExpressionManager;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
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
    @Mock
    private ProcessEngineConfigurationImpl processEngineConfiguration;

    private FlowableParticipantAssignmentCoordinator coordinator;

    @BeforeEach
    void setUp() {
        when(processEngineConfiguration.getExpressionManager())
                .thenReturn(new DefaultExpressionManager(Map.of()));
        coordinator = new FlowableParticipantAssignmentCoordinator(
                repositoryService, assignmentRuleService, participantDirectory, processEngineConfiguration);
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

    @Test
    void followsOnlyMatchingExclusiveGatewayBranch() {
        BpmnModel model = gatewayModel(new ExclusiveGateway(), false);
        when(repositoryService.getBpmnModel("leave:1:100")).thenReturn(model);
        when(assignmentRuleService.match(
                "tenant-1", "leave:1:100", "approvedTask", Map.of("approved", true)))
                .thenReturn(null);

        List<ParticipantRequirementView> requirements = coordinator.requirementsForStart(
                "tenant-1", "leave:1:100", Map.of("approved", true));

        assertThat(requirements).extracting(ParticipantRequirementView::activityId)
                .containsExactly("approvedTask");
    }

    @Test
    void followsExclusiveGatewayDefaultBranch() {
        BpmnModel model = gatewayModel(new ExclusiveGateway(), true);
        when(repositoryService.getBpmnModel("leave:1:100")).thenReturn(model);
        when(assignmentRuleService.match(
                "tenant-1", "leave:1:100", "rejectedTask", Map.of("approved", false)))
                .thenReturn(null);

        List<ParticipantRequirementView> requirements = coordinator.requirementsForStart(
                "tenant-1", "leave:1:100", Map.of("approved", false));

        assertThat(requirements).extracting(ParticipantRequirementView::activityId)
                .containsExactly("rejectedTask");
    }

    @Test
    void followsAllParallelGatewayBranches() {
        BpmnModel model = gatewayModel(new ParallelGateway(), false);
        when(repositoryService.getBpmnModel("leave:1:100")).thenReturn(model);
        when(assignmentRuleService.match(
                org.mockito.ArgumentMatchers.eq("tenant-1"),
                org.mockito.ArgumentMatchers.eq("leave:1:100"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Map.of())))
                .thenReturn(null);

        List<ParticipantRequirementView> requirements = coordinator.requirementsForStart(
                "tenant-1", "leave:1:100", Map.of());

        assertThat(requirements).extracting(ParticipantRequirementView::activityId)
                .containsExactly("approvedTask", "rejectedTask");
    }

    @Test
    void reportsMissingGatewayVariableInsteadOfReturningEveryBranch() {
        when(repositoryService.getBpmnModel("leave:1:100"))
                .thenReturn(gatewayModel(new ExclusiveGateway(), false));

        assertThatThrownBy(() -> coordinator.requirementsForStart(
                "tenant-1", "leave:1:100", Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Gateway condition cannot be evaluated");
    }

    @Test
    void followsConditionsAcrossNestedGateways() {
        BpmnModel model = nestedGatewayModel();
        when(repositoryService.getBpmnModel("leave:1:100")).thenReturn(model);
        when(assignmentRuleService.match(
                org.mockito.ArgumentMatchers.eq("tenant-1"),
                org.mockito.ArgumentMatchers.eq("leave:1:100"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(Map.of("approved", true))))
                .thenReturn(null);

        List<ParticipantRequirementView> requirements = coordinator.requirementsForStart(
                "tenant-1", "leave:1:100", Map.of("approved", true));

        assertThat(requirements).extracting(ParticipantRequirementView::activityId)
                .containsExactly("financeTask", "archiveTask");
    }

    @Test
    void followsOnlyMatchingConditionalFlowsAfterUserTask() {
        BpmnModel model = conditionalActivityModel(false);
        when(repositoryService.getBpmnModel("leave:1:100")).thenReturn(model);
        when(assignmentRuleService.match("tenant-1", "leave:1:100", "approvedTask", Map.of("approved", true)))
                .thenReturn(null);

        List<ParticipantRequirementView> requirements = coordinator.requirementsForStart(
                "tenant-1", "leave:1:100", Map.of("approved", true));

        assertThat(requirements).extracting(ParticipantRequirementView::activityId)
                .containsExactly("approvedTask");
    }

    @Test
    void followsActivityDefaultFlowWhenNoConditionalFlowMatches() {
        BpmnModel model = conditionalActivityModel(true);
        when(repositoryService.getBpmnModel("leave:1:100")).thenReturn(model);
        when(assignmentRuleService.match("tenant-1", "leave:1:100", "rejectedTask", Map.of("approved", false)))
                .thenReturn(null);

        List<ParticipantRequirementView> requirements = coordinator.requirementsForStart(
                "tenant-1", "leave:1:100", Map.of("approved", false));

        assertThat(requirements).extracting(ParticipantRequirementView::activityId)
                .containsExactly("rejectedTask");
    }

    @Test
    void rejectsNonBooleanConditionAfterActivity() {
        BpmnModel model = conditionalActivityModel(false);
        SequenceFlow conditional = model.getMainProcess().getFlowElement("to-approved", true)
                instanceof SequenceFlow flow ? flow : null;
        conditional.setConditionExpression("${approvedText}");
        when(repositoryService.getBpmnModel("leave:1:100")).thenReturn(model);

        assertThatThrownBy(() -> coordinator.requirementsForStart(
                "tenant-1", "leave:1:100", Map.of("approvedText", "yes")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Gateway condition must evaluate to a boolean: to-approved");
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

    private BpmnModel gatewayModel(Gateway gateway, boolean defaultBranch) {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("leave");
        StartEvent start = node(new StartEvent(), "start");
        gateway.setId("decision");
        UserTask approved = userTask("approvedTask");
        UserTask rejected = userTask("rejectedTask");
        SequenceFlow toGateway = flow("to-gateway", start, gateway, null);
        SequenceFlow toApproved = flow("to-approved", gateway, approved, "${approved}");
        SequenceFlow toRejected = flow("to-rejected", gateway, rejected, "${!approved}");
        if (defaultBranch) {
            toRejected.setConditionExpression(null);
            gateway.setDefaultFlow("to-rejected");
        }
        start.setOutgoingFlows(List.of(toGateway));
        gateway.setOutgoingFlows(List.of(toApproved, toRejected));
        process.addFlowElement(start);
        process.addFlowElement(gateway);
        process.addFlowElement(approved);
        process.addFlowElement(rejected);
        process.addFlowElement(toGateway);
        process.addFlowElement(toApproved);
        process.addFlowElement(toRejected);
        model.addProcess(process);
        return model;
    }

    private BpmnModel nestedGatewayModel() {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("leave");
        StartEvent start = node(new StartEvent(), "start");
        ExclusiveGateway decision = node(new ExclusiveGateway(), "decision");
        ParallelGateway approvedSplit = node(new ParallelGateway(), "approvedSplit");
        UserTask finance = userTask("financeTask");
        UserTask archive = userTask("archiveTask");
        UserTask rejected = userTask("rejectedTask");
        SequenceFlow toDecision = flow("to-decision", start, decision, null);
        SequenceFlow approved = flow("approved", decision, approvedSplit, "${approved}");
        SequenceFlow notApproved = flow("not-approved", decision, rejected, "${!approved}");
        SequenceFlow toFinance = flow("to-finance", approvedSplit, finance, null);
        SequenceFlow toArchive = flow("to-archive", approvedSplit, archive, null);
        start.setOutgoingFlows(List.of(toDecision));
        decision.setOutgoingFlows(List.of(approved, notApproved));
        approvedSplit.setOutgoingFlows(List.of(toFinance, toArchive));
        for (org.flowable.bpmn.model.FlowElement element : List.of(
                start, decision, approvedSplit, finance, archive, rejected,
                toDecision, approved, notApproved, toFinance, toArchive)) {
            process.addFlowElement(element);
        }
        model.addProcess(process);
        return model;
    }

    private BpmnModel conditionalActivityModel(boolean defaultBranch) {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("leave");
        StartEvent start = node(new StartEvent(), "start");
        ServiceTask review = node(new ServiceTask(), "review");
        UserTask approved = userTask("approvedTask");
        UserTask rejected = userTask("rejectedTask");
        SequenceFlow toReview = flow("to-review", start, review, null);
        SequenceFlow toApproved = flow("to-approved", review, approved, "${approved}");
        SequenceFlow toRejected = flow("to-rejected", review, rejected, null);
        if (defaultBranch) {
            review.setDefaultFlow("to-rejected");
        }
        start.setOutgoingFlows(List.of(toReview));
        review.setOutgoingFlows(List.of(toApproved, toRejected));
        for (org.flowable.bpmn.model.FlowElement element : List.of(
                start, review, approved, rejected, toReview, toApproved, toRejected)) {
            process.addFlowElement(element);
        }
        model.addProcess(process);
        return model;
    }

    private <T extends org.flowable.bpmn.model.FlowNode> T node(T node, String id) {
        node.setId(id);
        return node;
    }

    private UserTask userTask(String id) {
        UserTask task = node(new UserTask(), id);
        task.setAssignee("${assigneeService.getAssignee(execution)}");
        return task;
    }

    private SequenceFlow flow(String id, org.flowable.bpmn.model.FlowNode source,
                              org.flowable.bpmn.model.FlowNode target, String condition) {
        SequenceFlow flow = new SequenceFlow(source.getId(), target.getId());
        flow.setId(id);
        flow.setSourceFlowElement(source);
        flow.setTargetFlowElement(target);
        flow.setConditionExpression(condition);
        return flow;
    }
}
