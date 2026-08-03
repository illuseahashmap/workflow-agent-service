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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Service
public class WorkflowRuntimeServiceImpl implements WorkflowRuntimeService {

    private static final String ASSIGNEE_SUFFIX = "_assignee";
    private static final String ASSIGNEE_LIST_SUFFIX = "_assigneeList";

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final IdentityService identityService;
    private final RepositoryService repositoryService;
    private final WorkflowDefinitionService definitionService;
    private final TaskViewAssembler taskViewAssembler;
    private final FlowableParticipantAssignmentCoordinator participantCoordinator;
    private final ProcessInstanceTransactionExecutor transactionExecutor;
    private final CurrentPrincipalProvider principalProvider;
    private final TenantProvider tenantProvider;
    private final ParticipantDirectory participantDirectory;

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
        this.repositoryService = repositoryService;
        this.definitionService = definitionService;
        this.taskViewAssembler = taskViewAssembler;
        this.participantCoordinator = participantCoordinator;
        this.transactionExecutor = transactionExecutor;
        this.principalProvider = principalProvider;
        this.tenantProvider = tenantProvider;
        this.participantDirectory = participantDirectory;
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

    private ProcessDefinition requireProcessDefinition(String processDefinitionId, String tenantId) {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId)
                .singleResult();
        if (definition == null || !Objects.equals(tenantId, definition.getTenantId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist");
        }
        return definition;
    }

    private String resolveProcessDefinitionId(
            String processDefinitionKey, String requestedDefinitionId, String tenantId) {
        if (!StringUtils.hasText(processDefinitionKey)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Process definition key is required");
        }
        String normalizedDefinitionKey = processDefinitionKey.trim();
        if (StringUtils.hasText(requestedDefinitionId)) {
            String processDefinitionId = requestedDefinitionId.trim();
            ProcessDefinition definition = requireProcessDefinition(processDefinitionId, tenantId);
            if (!Objects.equals(normalizedDefinitionKey, definition.getKey())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "Process definition id does not match the requested process definition key");
            }
            return processDefinitionId;
        }
        return definitionService.getActiveVersion(normalizedDefinitionKey).processDefinitionId();
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
        Map<String, Object> variables = new HashMap<>(runtimeService.getVariables(processInstanceId));
        variables.putAll(ProcessVariablePolicy.clientVariables(clientVariables));
        return variables;
    }

    private String getProcessInstanceIdForTask(String taskId) {
        return getActiveTask(taskId).getProcessInstanceId();
    }

    private Task getActiveTask(String taskId) {
        Task task = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Active task does not exist");
        }
        assertTaskTenant(task);
        return task;
    }

    private Task getActiveTaskForOperation(String taskId, CurrentPrincipal actor) {
        Task task = getActiveTask(taskId);
        boolean servicePrincipal = "SERVICE".equals(actor.principalType());
        if (!servicePrincipal && !taskViewAssembler.canOperate(task, actor.username())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Current assignee cannot operate this task");
        }
        return task;
    }

    private String resolveRejectTarget(Task task, String requestedTarget) {
        String target = StringUtils.hasText(requestedTarget)
                ? requestedTarget.trim()
                : findFirstUserTaskId(task.getProcessDefinitionId());
        if (!StringUtils.hasText(target) || findUserTask(task.getProcessDefinitionId(), target) == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Reject target user task does not exist");
        }
        return target;
    }

    private String findFirstUserTaskId(String processDefinitionId) {
        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
        if (bpmnModel == null || bpmnModel.getMainProcess() == null) {
            return null;
        }
        return bpmnModel.getMainProcess().getFlowElements().stream()
                .filter(UserTask.class::isInstance)
                .map(FlowElement::getId)
                .findFirst()
                .orElse(null);
    }

    private UserTask findUserTask(String processDefinitionId, String activityId) {
        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
        if (bpmnModel == null || bpmnModel.getMainProcess() == null) {
            return null;
        }
        FlowElement flowElement = bpmnModel.getMainProcess().getFlowElement(activityId, true);
        return flowElement instanceof UserTask userTask ? userTask : null;
    }

    private Map<String, Object> buildRejectVariables(RejectTaskRequest request,
                                                     String processDefinitionId,
                                                     String targetActivityId) {
        Map<String, Object> variables = new HashMap<>(ProcessVariablePolicy.clientVariables(request.variables()));
        List<String> targetAssignees = normalizeList(request.targetAssignees());
        if (!targetAssignees.isEmpty()) {
            UserTask targetTask = findUserTask(processDefinitionId, targetActivityId);
            boolean collectionAssignment = targetTask != null && (targetTask.getLoopCharacteristics() != null
                    || !CollectionUtils.isEmpty(targetTask.getCandidateUsers())
                    || !CollectionUtils.isEmpty(targetTask.getCandidateGroups()));
            variables.put(targetActivityId + (collectionAssignment ? ASSIGNEE_LIST_SUFFIX : ASSIGNEE_SUFFIX),
                    collectionAssignment ? targetAssignees : targetAssignees.getFirst());
        }
        List<String> targetCandidateGroups = normalizeList(request.targetCandidateGroups());
        if (!targetCandidateGroups.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Candidate group assignment is not supported");
        }
        return variables;
    }

    private void validateTransferTarget(TransferTaskRequest request) {
        int targetCount = 0;
        if (StringUtils.hasText(request.targetAssignee())) {
            targetCount++;
        }
        if (!normalizeList(request.targetCandidateUsers()).isEmpty()) {
            targetCount++;
        }
        if (!normalizeList(request.targetCandidateGroups()).isEmpty()) {
            targetCount++;
        }
        if (targetCount != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Transfer target must contain exactly one target type");
        }
        if (!normalizeList(request.targetCandidateGroups()).isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Candidate group transfer is not supported");
        }
        List<String> targetUsers = StringUtils.hasText(request.targetAssignee())
                ? List.of(request.targetAssignee().trim().toLowerCase(java.util.Locale.ROOT))
                : normalizeList(request.targetCandidateUsers()).stream()
                        .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                        .toList();
        participantDirectory.requireTransferableUsernames(targetUsers);
    }

    private void applyTransferTarget(String taskId, TransferTaskRequest request) {
        if (StringUtils.hasText(request.targetAssignee())) {
            taskService.setAssignee(taskId,
                    request.targetAssignee().trim().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        taskService.setAssignee(taskId, null);
        normalizeList(request.targetCandidateUsers()).stream()
                .map(user -> user.toLowerCase(java.util.Locale.ROOT))
                .forEach(user -> taskService.addCandidateUser(taskId, user));
    }

    private void addComment(Task task, String type, String comment) {
        if (StringUtils.hasText(comment)) {
            taskService.addComment(task.getId(), task.getProcessInstanceId(), type, comment.trim());
        }
    }

    private void addTransferComment(Task task, TransferTaskRequest request, String previousAssignee, String operator) {
        String target = StringUtils.hasText(request.targetAssignee())
                ? "assignee:" + request.targetAssignee().trim()
                : !normalizeList(request.targetCandidateUsers()).isEmpty()
                    ? "candidateUsers:" + String.join("|", normalizeList(request.targetCandidateUsers()))
                    : "candidateGroups:" + String.join("|", normalizeList(request.targetCandidateGroups()));
        String message = "from=" + Objects.toString(previousAssignee, "")
                + ", to=" + target
                + ", operator=" + operator
                + (StringUtils.hasText(request.comment()) ? ", comment=" + request.comment().trim() : "");
        taskService.addComment(task.getId(), task.getProcessInstanceId(), "transfer", message);
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
    }

    private ApproveTaskResult buildApproveResult(String completedTaskId, String processInstanceId) {
        List<TaskView> nextTasks = activeTasks(processInstanceId);
        return new ApproveTaskResult(completedTaskId, processInstanceId, nextTasks.isEmpty(), nextTasks);
    }

    private List<Task> activeTaskEntities(String processInstanceId) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .orderByTaskCreateTime()
                .asc()
                .list();
    }

    private List<TaskView> activeTasks(String processInstanceId) {
        return activeTaskEntities(processInstanceId).stream()
                .peek(this::assertTaskTenant)
                .map(taskViewAssembler::fromActiveTask)
                .toList();
    }

    private void clearCandidates(String taskId) {
        for (IdentityLink identityLink : taskService.getIdentityLinksForTask(taskId)) {
            if (!"candidate".equals(identityLink.getType())) {
                continue;
            }
            if (StringUtils.hasText(identityLink.getUserId())) {
                taskService.deleteCandidateUser(taskId, identityLink.getUserId());
            }
            if (StringUtils.hasText(identityLink.getGroupId())) {
                taskService.deleteCandidateGroup(taskId, identityLink.getGroupId());
            }
        }
    }

    private void assertTaskTenant(Task task) {
        assertTenant(task.getTenantId());
    }

    private void assertTenant(String tenantId) {
        if (!tenantProvider.current().tenantId().equals(tenantId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Cross-tenant workflow access is forbidden");
        }
    }
}
