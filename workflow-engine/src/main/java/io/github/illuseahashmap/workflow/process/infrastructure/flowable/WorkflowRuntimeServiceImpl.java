package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.WorkflowDefinitionService;
import io.github.illuseahashmap.workflow.process.application.WorkflowRuntimeService;
import io.github.illuseahashmap.workflow.process.application.dto.ApproveTaskRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ApproveTaskResult;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessStatusView;
import io.github.illuseahashmap.workflow.process.application.dto.RejectTaskRequest;
import io.github.illuseahashmap.workflow.process.application.dto.StartProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.StartProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.TaskView;
import io.github.illuseahashmap.workflow.process.application.dto.TransferTaskRequest;
import io.github.illuseahashmap.workflow.process.application.port.ProcessInstanceLock;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal;
import io.github.illuseahashmap.workflow.shared.context.CurrentPrincipalProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
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
    private static final String CANDIDATE_GROUP_LIST_SUFFIX = "_candidateGroupList";

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final RepositoryService repositoryService;
    private final WorkflowDefinitionService definitionService;
    private final TaskViewAssembler taskViewAssembler;
    private final ProcessInstanceLock processInstanceLock;
    private final CurrentPrincipalProvider principalProvider;

    public WorkflowRuntimeServiceImpl(RuntimeService runtimeService,
                                      TaskService taskService,
                                      HistoryService historyService,
                                      RepositoryService repositoryService,
                                      WorkflowDefinitionService definitionService,
                                      TaskViewAssembler taskViewAssembler,
                                      ProcessInstanceLock processInstanceLock,
                                      CurrentPrincipalProvider principalProvider) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.repositoryService = repositoryService;
        this.definitionService = definitionService;
        this.taskViewAssembler = taskViewAssembler;
        this.processInstanceLock = processInstanceLock;
        this.principalProvider = principalProvider;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StartProcessResult start(StartProcessRequest request) {
        TenantContext.TenantInfo tenant = TenantContext.current();
        String processDefinitionId;
        if (StringUtils.hasText(request.processDefinitionId())) {
            processDefinitionId = request.processDefinitionId().trim();
            validateProcessDefinitionTenant(processDefinitionId, tenant.tenantId());
        } else {
            processDefinitionId = definitionService.getActiveVersion(request.processDefinitionKey())
                    .processDefinitionId();
        }
        ProcessInstance instance = runtimeService.startProcessInstanceById(
                processDefinitionId,
                request.businessKey(),
                enrichVariables(request.variables(), tenant));
        return new StartProcessResult(
                instance.getProcessInstanceId(),
                instance.getProcessDefinitionId(),
                instance.getProcessDefinitionKey(),
                instance.getBusinessKey(),
                activeTasks(instance.getProcessInstanceId()));
    }

    @Override
    public ProcessStatusView getProcessStatus(String processInstanceId) {
        String tenantId = TenantContext.current().tenantId();
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
    @Transactional(rollbackFor = Exception.class)
    public ApproveTaskResult approve(ApproveTaskRequest request) {
        String processInstanceId = getProcessInstanceIdForTask(request.taskId());
        return processInstanceLock.execute(processInstanceId, () -> {
            CurrentPrincipal actor = principalProvider.current();
            Task task = getActiveTaskForOperation(request.taskId(), actor);
            taskViewAssembler.claimIfNeeded(task, actor.username());
            addComment(task, "agree", request.comment());
            taskService.complete(task.getId(), safeVariables(request.variables()));
            return buildApproveResult(task.getId(), processInstanceId);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApproveTaskResult autoComplete(ApproveTaskRequest request) {
        String processInstanceId = getProcessInstanceIdForTask(request.taskId());
        return processInstanceLock.execute(processInstanceId, () -> {
            Task task = getActiveTask(request.taskId());
            if (StringUtils.hasText(request.currentAssignee())
                    && !Objects.equals(request.currentAssignee(), task.getAssignee())) {
                taskService.setAssignee(task.getId(), request.currentAssignee());
            }
            addComment(task, "autoComplete", request.comment());
            taskService.complete(task.getId(), safeVariables(request.variables()));
            return buildApproveResult(task.getId(), processInstanceId);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApproveTaskResult reject(RejectTaskRequest request) {
        String processInstanceId = getProcessInstanceIdForTask(request.taskId());
        return processInstanceLock.execute(processInstanceId, () -> {
            CurrentPrincipal actor = principalProvider.current();
            Task task = getActiveTaskForOperation(request.taskId(), actor);
            String targetActivityId = resolveRejectTarget(task, request.targetActivityId());
            Map<String, Object> variables = buildRejectVariables(request, task.getProcessDefinitionId(), targetActivityId);
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
    @Transactional(rollbackFor = Exception.class)
    public TaskView transfer(TransferTaskRequest request) {
        String processInstanceId = getProcessInstanceIdForTask(request.taskId());
        return processInstanceLock.execute(processInstanceId, () -> {
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

    private void validateProcessDefinitionTenant(String processDefinitionId, String tenantId) {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId)
                .singleResult();
        if (definition == null || !Objects.equals(tenantId, definition.getTenantId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist");
        }
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
        Map<String, Object> variables = new HashMap<>(safeVariables(request.variables()));
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
            variables.put(targetActivityId + CANDIDATE_GROUP_LIST_SUFFIX, targetCandidateGroups);
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
    }

    private void applyTransferTarget(String taskId, TransferTaskRequest request) {
        if (StringUtils.hasText(request.targetAssignee())) {
            taskService.setAssignee(taskId, request.targetAssignee().trim());
            return;
        }
        taskService.setAssignee(taskId, null);
        normalizeList(request.targetCandidateUsers()).forEach(user -> taskService.addCandidateUser(taskId, user));
        normalizeList(request.targetCandidateGroups()).forEach(group -> taskService.addCandidateGroup(taskId, group));
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

    private Map<String, Object> enrichVariables(Map<String, Object> variables, TenantContext.TenantInfo tenant) {
        Map<String, Object> enriched = new HashMap<>(safeVariables(variables));
        enriched.put("tenantId", tenant.tenantId());
        enriched.put("tenantCode", tenant.tenantCode());
        return enriched;
    }

    private Map<String, Object> safeVariables(Map<String, Object> variables) {
        return variables == null ? Map.of() : variables;
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
        if (!TenantContext.current().tenantId().equals(tenantId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Cross-tenant workflow access is forbidden");
        }
    }
}
