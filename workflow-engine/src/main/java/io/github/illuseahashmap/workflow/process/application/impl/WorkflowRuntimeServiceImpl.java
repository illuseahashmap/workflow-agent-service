package io.github.illuseahashmap.workflow.process.application.impl;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.process.application.dto.ApproveTaskRequest;
import io.github.illuseahashmap.workflow.process.application.dto.ApproveTaskResult;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessStatusView;
import io.github.illuseahashmap.workflow.process.application.dto.RejectTaskRequest;
import io.github.illuseahashmap.workflow.process.application.dto.StartProcessRequest;
import io.github.illuseahashmap.workflow.process.application.dto.StartProcessResult;
import io.github.illuseahashmap.workflow.process.application.dto.TaskView;
import io.github.illuseahashmap.workflow.process.application.dto.TransferTaskRequest;
import io.github.illuseahashmap.workflow.process.application.assembler.TaskViewAssembler;
import io.github.illuseahashmap.workflow.process.application.WorkflowDefinitionService;
import io.github.illuseahashmap.workflow.process.application.WorkflowRuntimeService;
import io.github.illuseahashmap.workflow.tenant.domain.TenantContext;
import java.util.List;
import java.util.Map;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
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

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final WorkflowDefinitionService definitionService;
    private final TaskViewAssembler taskViewAssembler;

    public WorkflowRuntimeServiceImpl(RuntimeService runtimeService,
                                      TaskService taskService,
                                      HistoryService historyService,
                                      WorkflowDefinitionService definitionService,
                                      TaskViewAssembler taskViewAssembler) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.definitionService = definitionService;
        this.taskViewAssembler = taskViewAssembler;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StartProcessResult start(StartProcessRequest request) {
        TenantContext.TenantInfo tenant = TenantContext.current();
        String processDefinitionId = StringUtils.hasText(request.processDefinitionId())
                ? request.processDefinitionId()
                : definitionService.getActiveVersion(request.processDefinitionKey()).processDefinitionId();
        ProcessInstance instance = runtimeService.startProcessInstanceById(
                processDefinitionId,
                request.businessKey(),
                enrichVariables(request.variables(), tenant));
        List<TaskView> activeTasks = activeTasks(instance.getProcessInstanceId());
        return new StartProcessResult(
                instance.getProcessInstanceId(),
                instance.getProcessDefinitionId(),
                instance.getProcessDefinitionKey(),
                instance.getBusinessKey(),
                activeTasks
        );
    }

    @Override
    public ProcessStatusView getProcessStatus(String processInstanceId) {
        TenantContext.TenantInfo tenant = TenantContext.current();
        ProcessInstance runningInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceTenantId(tenant.tenantId())
                .processInstanceId(processInstanceId)
                .singleResult();
        if (runningInstance != null) {
            return new ProcessStatusView(
                    runningInstance.getProcessInstanceId(),
                    runningInstance.getProcessDefinitionId(),
                    runningInstance.getBusinessKey(),
                    runningInstance.isSuspended() ? "SUSPENDED" : "RUNNING",
                    activeTasks(processInstanceId)
            );
        }
        HistoricProcessInstance historicInstance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceTenantId(tenant.tenantId())
                .processInstanceId(processInstanceId)
                .singleResult();
        if (historicInstance == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process instance does not exist");
        }
        return new ProcessStatusView(
                historicInstance.getId(),
                historicInstance.getProcessDefinitionId(),
                historicInstance.getBusinessKey(),
                "FINISHED_OR_NOT_FOUND",
                List.of()
        );
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
        Task task = getActiveTaskForOperation(request.taskId(), request.currentAssignee(), request.currentCandidateGroups());
        if (StringUtils.hasText(request.comment())) {
            taskService.addComment(task.getId(), task.getProcessInstanceId(), request.comment());
        }
        taskViewAssembler.claimIfNeeded(task, request.currentAssignee());
        taskService.complete(task.getId(), request.variables() == null ? Map.of() : request.variables());
        List<TaskView> nextTasks = activeTasks(task.getProcessInstanceId());
        return new ApproveTaskResult(task.getId(), task.getProcessInstanceId(), nextTasks.isEmpty(), nextTasks);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApproveTaskResult reject(RejectTaskRequest request) {
        Task task = getActiveTaskForOperation(request.taskId(), request.currentAssignee(), request.currentCandidateGroups());
        if (StringUtils.hasText(request.comment())) {
            taskService.addComment(task.getId(), task.getProcessInstanceId(), request.comment());
        }
        if (!CollectionUtils.isEmpty(request.variables())) {
            runtimeService.setVariables(task.getProcessInstanceId(), request.variables());
        }
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(task.getProcessInstanceId())
                .moveActivityIdTo(task.getTaskDefinitionKey(), request.targetActivityId())
                .changeState();
        List<TaskView> nextTasks = activeTasks(task.getProcessInstanceId());
        return new ApproveTaskResult(task.getId(), task.getProcessInstanceId(), nextTasks.isEmpty(), nextTasks);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskView transfer(TransferTaskRequest request) {
        Task task = getActiveTaskForOperation(request.taskId(), request.currentAssignee(), request.currentCandidateGroups());
        validateTransferTarget(request);
        if (StringUtils.hasText(request.comment())) {
            taskService.addComment(task.getId(), task.getProcessInstanceId(), request.comment());
        }
        clearCandidates(task.getId());
        if (StringUtils.hasText(request.targetAssignee())) {
            taskService.setAssignee(task.getId(), request.targetAssignee());
        } else {
            taskService.setAssignee(task.getId(), null);
            if (!CollectionUtils.isEmpty(request.targetCandidateUsers())) {
                request.targetCandidateUsers().forEach(user -> taskService.addCandidateUser(task.getId(), user));
            }
            if (!CollectionUtils.isEmpty(request.targetCandidateGroups())) {
                request.targetCandidateGroups().forEach(group -> taskService.addCandidateGroup(task.getId(), group));
            }
        }
        Task latestTask = taskService.createTaskQuery().taskId(task.getId()).singleResult();
        return taskViewAssembler.fromActiveTask(latestTask);
    }

    private Map<String, Object> enrichVariables(Map<String, Object> variables, TenantContext.TenantInfo tenant) {
        Map<String, Object> enrichedVariables = variables == null ? new java.util.HashMap<>() : new java.util.HashMap<>(variables);
        enrichedVariables.put("tenantId", tenant.tenantId());
        enrichedVariables.put("tenantCode", tenant.tenantCode());
        return enrichedVariables;
    }

    private List<TaskView> activeTasks(String processInstanceId) {
        return taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .list()
                .stream()
                .peek(this::assertTaskTenant)
                .map(taskViewAssembler::fromActiveTask)
                .toList();
    }

    private Task getActiveTaskForOperation(String taskId, String currentAssignee, List<String> currentCandidateGroups) {
        Task task = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Active task does not exist");
        }
        assertTaskTenant(task);
        if (!taskViewAssembler.canOperate(task, currentAssignee, currentCandidateGroups)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Current assignee cannot operate this task");
        }
        return task;
    }

    private void validateTransferTarget(TransferTaskRequest request) {
        int targetCount = 0;
        if (StringUtils.hasText(request.targetAssignee())) {
            targetCount++;
        }
        if (!CollectionUtils.isEmpty(request.targetCandidateUsers())) {
            targetCount++;
        }
        if (!CollectionUtils.isEmpty(request.targetCandidateGroups())) {
            targetCount++;
        }
        if (targetCount != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Transfer target must contain exactly one target type");
        }
    }

    private void clearCandidates(String taskId) {
        List<IdentityLink> identityLinks = taskService.getIdentityLinksForTask(taskId);
        for (IdentityLink identityLink : identityLinks) {
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
