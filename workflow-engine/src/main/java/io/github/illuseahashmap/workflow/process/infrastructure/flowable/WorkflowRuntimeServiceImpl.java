package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.WorkflowDefinitionService;
import io.github.illuseahashmap.workflow.process.application.WorkflowRuntimeService;
import io.github.illuseahashmap.workflow.process.application.ProcessVariablePolicy;
import io.github.illuseahashmap.workflow.process.application.dto.ApproveTaskRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ApproveTaskResult;
import io.github.illuseahashmap.workflow.process.application.dto.ParticipantAssignment;
import io.github.illuseahashmap.workflow.process.application.dto.ParticipantRequirementView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessParticipantRequirementsRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessStatusView;
import io.github.illuseahashmap.workflow.process.application.dto.RejectTaskRequest;
import io.github.illuseahashmap.workflow.process.application.dto.StartProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.StartProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.TaskView;
import io.github.illuseahashmap.workflow.process.application.dto.TaskParticipantAction;
import io.github.illuseahashmap.workflow.process.application.dto.TaskParticipantRequirementsRequest;
import io.github.illuseahashmap.workflow.process.application.dto.TransferTaskRequest;
import io.github.illuseahashmap.workflow.process.application.port.ParticipantDirectory;
import io.github.illuseahashmap.workflow.process.infrastructure.lock.ProcessInstanceTransactionExecutor;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Service
public class WorkflowRuntimeServiceImpl implements WorkflowRuntimeService {

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final IdentityService identityService;
    private final TaskViewAssembler taskViewAssembler;
    private final FlowableParticipantAssignmentCoordinator participantCoordinator;
    private final ProcessInstanceTransactionExecutor transactionExecutor;
    private final CurrentPrincipalProvider principalProvider;
    private final TenantProvider tenantProvider;
    private final WorkflowTaskOperationSupport taskSupport;

    public WorkflowRuntimeServiceImpl(RuntimeService runtimeService,
                                      TaskService taskService,
                                      HistoryService historyService,
                                      IdentityService identityService,
                                      RepositoryService repositoryService,
                                      WorkflowDefinitionService definitionService,
                                      TaskViewAssembler taskViewAssembler,
                                      FlowableParticipantAssignmentCoordinator participantCoordinator,
                                      ProcessInstanceTransactionExecutor transactionExecutor,
                                      CurrentPrincipalProvider principalProvider,
                                      TenantProvider tenantProvider,
                                      ParticipantDirectory participantDirectory) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.identityService = identityService;
        this.taskViewAssembler = taskViewAssembler;
        this.participantCoordinator = participantCoordinator;
        this.transactionExecutor = transactionExecutor;
        this.principalProvider = principalProvider;
        this.tenantProvider = tenantProvider;
        this.taskSupport = new WorkflowTaskOperationSupport(
                runtimeService, taskService, repositoryService, definitionService,
                taskViewAssembler, participantDirectory, tenantProvider);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StartProcessResult start(StartProcessRequest request) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        String processDefinitionId = resolveProcessDefinitionId(
                request.processDefinitionKey(), request.processDefinitionId(), tenant.tenantId());
        Map<String, Object> variables = ProcessVariablePolicy.clientVariables(request.variables());
        variables.putAll(participantCoordinator.prepareForStart(
                tenant.tenantId(), processDefinitionId, variables, request.participantAssignments()));
        CurrentPrincipal actor = principalProvider.current();
        ProcessInstance instance;
        identityService.setAuthenticatedUserId(actor.username());
        try {
            instance = runtimeService.startProcessInstanceById(
                    processDefinitionId,
                    request.businessKey(),
                    ProcessVariablePolicy.enrichTrustedWithTenant(variables, tenant));
        } finally {
            identityService.setAuthenticatedUserId(null);
        }
        return new StartProcessResult(
                instance.getProcessInstanceId(),
                instance.getProcessDefinitionId(),
                instance.getProcessDefinitionKey(),
                instance.getBusinessKey(),
                activeTasks(instance.getProcessInstanceId()));
    }

    @Override
    public List<ParticipantRequirementView> getStartParticipantRequirements(
            ProcessParticipantRequirementsRequest request) {
        TenantContext.TenantInfo tenant = tenantProvider.current();
        String processDefinitionId = resolveProcessDefinitionId(
                request.processDefinitionKey(), request.processDefinitionId(), tenant.tenantId());
        return participantCoordinator.requirementsForStart(
                tenant.tenantId(), processDefinitionId,
                ProcessVariablePolicy.clientVariables(request.variables()));
    }

    @Override
    public ProcessStatusView getProcessStatus(String processInstanceId) {
        String tenantId = tenantProvider.current().tenantId();
        ProcessInstance runningInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceTenantId(tenantId)
                .processInstanceId(processInstanceId)
                .singleResult();
        if (runningInstance != null) {
            return new ProcessStatusView(
                    runningInstance.getProcessInstanceId(),
                    runningInstance.getProcessDefinitionId(),
                    runningInstance.getBusinessKey(),
                    runningInstance.isSuspended() ? "SUSPENDED" : "RUNNING",
                    activeTasks(processInstanceId));
        }
        HistoricProcessInstance historicInstance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceTenantId(tenantId)
                .processInstanceId(processInstanceId)
                .singleResult();
        if (historicInstance == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process instance does not exist");
        }
        return new ProcessStatusView(
                historicInstance.getId(),
                historicInstance.getProcessDefinitionId(),
                historicInstance.getBusinessKey(),
                "FINISHED",
                List.of());
    }

    @Override
    public TaskView getTaskStatus(String taskId) {
        Task activeTask = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (activeTask != null) {
            assertTaskTenant(activeTask);
            return taskViewAssembler.fromActiveTask(activeTask);
        }
        HistoricTaskInstance historicTask = historyService.createHistoricTaskInstanceQuery()
                .taskId(taskId)
                .singleResult();
        if (historicTask == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Task does not exist");
        }
        assertTenant(historicTask.getTenantId());
        return taskViewAssembler.fromHistoricTask(historicTask);
    }

    @Override
    public List<ParticipantRequirementView> getTaskParticipantRequirements(
            TaskParticipantRequirementsRequest request) {
        Task task = getActiveTask(request.taskId());
        Map<String, Object> variables = processVariablesWithClientOverrides(
                task.getProcessInstanceId(), request.variables());
        return participantCoordinator.requirementsForTask(
                tenantProvider.current().tenantId(), task, request.action(),
                request.targetActivityId(), variables);
    }

    @Override
    public ApproveTaskResult approve(ApproveTaskRequest request) {
        String processInstanceId = getProcessInstanceIdForTask(request.taskId());
        return transactionExecutor.execute(processInstanceId, () -> {
            CurrentPrincipal actor = principalProvider.current();
            Task task = getActiveTaskForOperation(request.taskId(), actor);
            taskViewAssembler.claimIfNeeded(task, actor.username());
            addComment(task, "agree", request.comment());
            Map<String, Object> variables = completionVariables(
                    task, TaskParticipantAction.APPROVE, null,
                    request.variables(), request.participantAssignments());
            taskService.complete(task.getId(), variables);
            return buildApproveResult(task.getId(), processInstanceId);
        });
    }

    @Override
    public ApproveTaskResult autoComplete(ApproveTaskRequest request) {
        String processInstanceId = getProcessInstanceIdForTask(request.taskId());
        return transactionExecutor.execute(processInstanceId, () -> {
            Task task = getActiveTask(request.taskId());
            if (StringUtils.hasText(request.currentAssignee())
                    && !Objects.equals(request.currentAssignee(), task.getAssignee())) {
                taskService.setAssignee(task.getId(), request.currentAssignee());
            }
            addComment(task, "autoComplete", request.comment());
            Map<String, Object> variables = completionVariables(
                    task, TaskParticipantAction.APPROVE, null,
                    request.variables(), request.participantAssignments());
            taskService.complete(task.getId(), variables);
            return buildApproveResult(task.getId(), processInstanceId);
        });
    }

    @Override
    public ApproveTaskResult reject(RejectTaskRequest request) {
        String processInstanceId = getProcessInstanceIdForTask(request.taskId());
        return transactionExecutor.execute(processInstanceId, () -> {
            CurrentPrincipal actor = principalProvider.current();
            Task task = getActiveTaskForOperation(request.taskId(), actor);
            String targetActivityId = resolveRejectTarget(task, request.targetActivityId());
            Map<String, Object> variables = buildRejectVariables(request, task.getProcessDefinitionId(), targetActivityId);
            Map<String, Object> contextVariables = processVariablesWithClientOverrides(
                    processInstanceId, request.variables());
            if (CollectionUtils.isEmpty(request.targetAssignees())
                    && CollectionUtils.isEmpty(request.targetCandidateGroups())) {
                variables.putAll(participantCoordinator.prepareForTask(
                        tenantProvider.current().tenantId(), task, TaskParticipantAction.REJECT,
                        targetActivityId, contextVariables, request.participantAssignments()));
            }
            if (!variables.isEmpty()) {
                runtimeService.setVariables(processInstanceId, variables);
            }
            addComment(task, "reject", request.comment());
            List<String> executionIds = activeTaskEntities(processInstanceId).stream()
                    .map(Task::getExecutionId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (executionIds.size() > 1) {
                runtimeService.createChangeActivityStateBuilder()
                        .processInstanceId(processInstanceId)
                        .moveExecutionsToSingleActivityId(executionIds, targetActivityId)
                        .changeState();
            } else {
                runtimeService.createChangeActivityStateBuilder()
                        .processInstanceId(processInstanceId)
                        .moveExecutionToActivityId(task.getExecutionId(), targetActivityId)
                        .changeState();
            }
            return buildApproveResult(task.getId(), processInstanceId);
        });
    }

    @Override
    public TaskView transfer(TransferTaskRequest request) {
        String processInstanceId = getProcessInstanceIdForTask(request.taskId());
        return transactionExecutor.execute(processInstanceId, () -> {
            CurrentPrincipal actor = principalProvider.current();
            Task task = getActiveTaskForOperation(request.taskId(), actor);
            validateTransferTarget(request);
            String previousAssignee = task.getAssignee();
            clearCandidates(task.getId());
            applyTransferTarget(task.getId(), request);
            addTransferComment(task, request, previousAssignee, actor.username());
            return taskViewAssembler.fromActiveTask(getActiveTask(task.getId()));
        });
    }

    private String resolveProcessDefinitionId(
            String processDefinitionKey, String requestedDefinitionId, String tenantId) {
        return taskSupport.resolveProcessDefinitionId(processDefinitionKey, requestedDefinitionId, tenantId);
    }

    private Map<String, Object> completionVariables(
            Task task, TaskParticipantAction action, String targetActivityId,
            Map<String, Object> clientVariables, List<ParticipantAssignment> participantAssignments) {
        Map<String, Object> completionVariables = ProcessVariablePolicy.clientVariables(clientVariables);
        Map<String, Object> contextVariables = processVariablesWithClientOverrides(
                task.getProcessInstanceId(), completionVariables);
        completionVariables.putAll(participantCoordinator.prepareForTask(
                tenantProvider.current().tenantId(), task, action,
                targetActivityId, contextVariables, participantAssignments));
        return completionVariables;
    }

    private Map<String, Object> processVariablesWithClientOverrides(
            String processInstanceId, Map<String, Object> clientVariables) {
        return taskSupport.processVariablesWithClientOverrides(processInstanceId, clientVariables);
    }

    private String getProcessInstanceIdForTask(String taskId) {
        return taskSupport.activeTask(taskId).getProcessInstanceId();
    }

    private Task getActiveTask(String taskId) {
        return taskSupport.activeTask(taskId);
    }

    private Task getActiveTaskForOperation(String taskId, CurrentPrincipal actor) {
        return taskSupport.activeTaskForOperation(taskId, actor);
    }

    private String resolveRejectTarget(Task task, String requestedTarget) {
        return taskSupport.resolveRejectTarget(task, requestedTarget);
    }

    private Map<String, Object> buildRejectVariables(RejectTaskRequest request,
                                                     String processDefinitionId,
                                                     String targetActivityId) {
        return taskSupport.buildRejectVariables(request, processDefinitionId, targetActivityId);
    }

    private void validateTransferTarget(TransferTaskRequest request) {
        taskSupport.validateTransferTarget(request);
    }

    private void applyTransferTarget(String taskId, TransferTaskRequest request) {
        taskSupport.applyTransferTarget(taskId, request);
    }

    private void addComment(Task task, String type, String comment) {
        taskSupport.addComment(task, type, comment);
    }

    private void addTransferComment(Task task, TransferTaskRequest request, String previousAssignee, String operator) {
        taskSupport.addTransferComment(task, request, previousAssignee, operator);
    }

    private ApproveTaskResult buildApproveResult(String completedTaskId, String processInstanceId) {
        return taskSupport.buildApproveResult(completedTaskId, processInstanceId);
    }

    private List<Task> activeTaskEntities(String processInstanceId) {
        return taskSupport.activeTaskEntities(processInstanceId);
    }

    private List<TaskView> activeTasks(String processInstanceId) {
        return taskSupport.activeTasks(processInstanceId);
    }

    private void clearCandidates(String taskId) {
        taskSupport.clearCandidates(taskId);
    }

    private void assertTaskTenant(Task task) {
        taskSupport.assertTaskTenant(task);
    }

    private void assertTenant(String tenantId) {
        taskSupport.assertTenant(tenantId);
    }
}
