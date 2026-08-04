package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.process.application.WorkflowDefinitionService;
import io.github.illuseahashmap.workflow.process.application.dto.ActiveProcessVersionResult;
import io.github.illuseahashmap.workflow.process.application.dto.RejectTaskRequest;
import io.github.illuseahashmap.workflow.process.application.dto.StartProcessRequest;
import io.github.illuseahashmap.workflow.process.infrastructure.lock.ProcessInstanceTransactionExecutor;
import io.github.illuseahashmap.workflow.process.application.port.ParticipantDirectory;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkflowRuntimeServiceImplTest {

    @Mock
    private RuntimeService runtimeService;
    @Mock
    private TaskService taskService;
    @Mock
    private HistoryService historyService;
    @Mock
    private IdentityService identityService;
    @Mock
    private RepositoryService repositoryService;
    @Mock
    private WorkflowDefinitionService definitionService;
    @Mock
    private TaskViewAssembler taskViewAssembler;
    @Mock
    private FlowableParticipantAssignmentCoordinator participantCoordinator;
    @Mock
    private ProcessInstanceTransactionExecutor transactionExecutor;
    @Mock
    private CurrentPrincipalProvider principalProvider;
    @Mock
    private TenantProvider tenantProvider;
    @Mock
    private ParticipantDirectory participantDirectory;
    @Mock
    private ProcessInstance processInstance;
    @Mock
    private TaskQuery taskQuery;
    @Mock
    private Task task;
    @Mock
    private ProcessDefinitionQuery processDefinitionQuery;
    @Mock
    private ProcessDefinition processDefinition;

    @Test
    void recordsAuthenticatedUserWhileStartingProcess() {
        WorkflowRuntimeServiceImpl service = service();
        when(tenantProvider.current()).thenReturn(
                new TenantContext.TenantInfo("tenant-1", "tenant-one", "租户一"));
        when(principalProvider.current()).thenReturn(new CurrentPrincipal(
                "USER",
                "user-1",
                "admin",
                "平台管理员",
                "tenant-one",
                Set.of("PLATFORM_ADMIN"),
                Set.of("workflow:instance:operate")));
        when(definitionService.getActiveVersion("leave-approval")).thenReturn(
                new ActiveProcessVersionResult(
                        "tenant-1", "leave-approval", "leave-approval:1:100", 1, "admin",
                        OffsetDateTime.now()));
        when(participantCoordinator.prepareForStart(
                "tenant-1", "leave-approval:1:100", Map.of(), List.of()))
                .thenReturn(Map.of());
        when(runtimeService.startProcessInstanceById(
                org.mockito.ArgumentMatchers.eq("leave-approval:1:100"),
                org.mockito.ArgumentMatchers.eq("LEAVE-001"),
                anyMap())).thenReturn(processInstance);
        when(processInstance.getProcessInstanceId()).thenReturn("instance-1");
        when(processInstance.getProcessDefinitionId()).thenReturn("leave-approval:1:100");
        when(processInstance.getProcessDefinitionKey()).thenReturn("leave-approval");
        when(processInstance.getBusinessKey()).thenReturn("LEAVE-001");
        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.processInstanceId("instance-1")).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.orderByTaskCreateTime()).thenReturn(taskQuery);
        when(taskQuery.asc()).thenReturn(taskQuery);
        when(taskQuery.list()).thenReturn(List.of());

        service.start(new StartProcessRequest(
                "leave-approval", null, "LEAVE-001", Map.of(), List.of()));

        InOrder ordered = inOrder(identityService, runtimeService);
        ordered.verify(identityService).setAuthenticatedUserId("admin");
        ordered.verify(runtimeService).startProcessInstanceById(
                org.mockito.ArgumentMatchers.eq("leave-approval:1:100"),
                org.mockito.ArgumentMatchers.eq("LEAVE-001"),
                anyMap());
        ordered.verify(identityService).setAuthenticatedUserId(null);
    }

    @Test
    void rejectsMismatchedProcessDefinitionIdAndKey() {
        when(tenantProvider.current()).thenReturn(
                new TenantContext.TenantInfo("tenant-1", "tenant-one", "Tenant One"));
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(processDefinitionQuery);
        when(processDefinitionQuery.processDefinitionId("expense:2:200")).thenReturn(processDefinitionQuery);
        when(processDefinitionQuery.singleResult()).thenReturn(processDefinition);
        when(processDefinition.getTenantId()).thenReturn("tenant-1");
        when(processDefinition.getKey()).thenReturn("expense");

        assertThatThrownBy(() -> service().start(new StartProcessRequest(
                "leave", "expense:2:200", null, Map.of(), List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Process definition id does not match the requested process definition key");
    }

    @Test
    void rejectsUnavailableExplicitTargetAssigneeBeforeChangingFlowableState() {
        when(tenantProvider.current()).thenReturn(
                new TenantContext.TenantInfo("tenant-1", "tenant-one", "Tenant One"));
        when(principalProvider.current()).thenReturn(new CurrentPrincipal(
                "USER", "user-1", "operator", "Operator", "tenant-one",
                Set.of("TENANT_ADMIN"), Set.of("workflow:instance:operate")));
        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId("task-1")).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(task);
        when(task.getProcessInstanceId()).thenReturn("instance-1");
        when(task.getProcessDefinitionId()).thenReturn("leave:1:100");
        when(task.getTenantId()).thenReturn("tenant-1");
        when(taskViewAssembler.canOperate(task, "operator")).thenReturn(true);
        when(transactionExecutor.execute(eq("instance-1"), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        when(repositoryService.getBpmnModel("leave:1:100")).thenReturn(modelWithUserTask("review"));
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "Users do not belong to the current tenant: outsider"))
                .when(participantDirectory).requireUsableUsernames(List.of("outsider"));

        assertThatThrownBy(() -> service().reject(new RejectTaskRequest(
                "task-1", "operator", List.of(), "review", null,
                List.of("outsider"), List.of(), Map.of(), List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Users do not belong to the current tenant: outsider");

        verify(runtimeService, never()).setVariables(eq("instance-1"), anyMap());
        verify(participantDirectory).requireUsableUsernames(List.of("outsider"));
    }

    private BpmnModel modelWithUserTask(String activityId) {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("leave");
        UserTask userTask = new UserTask();
        userTask.setId(activityId);
        process.addFlowElement(userTask);
        model.addProcess(process);
        return model;
    }

    private WorkflowRuntimeServiceImpl service() {
        return new WorkflowRuntimeServiceImpl(
                runtimeService, taskService, historyService, identityService,
                repositoryService, definitionService, taskViewAssembler,
                participantCoordinator, transactionExecutor, principalProvider,
                tenantProvider, participantDirectory);
    }
}
