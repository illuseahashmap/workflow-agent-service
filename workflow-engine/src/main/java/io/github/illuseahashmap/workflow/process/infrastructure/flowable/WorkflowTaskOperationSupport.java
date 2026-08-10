package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.process.application.ProcessVariablePolicy;
import io.github.illuseahashmap.workflow.process.application.WorkflowDefinitionService;
import io.github.illuseahashmap.workflow.process.application.dto.ApproveTaskResult;
import io.github.illuseahashmap.workflow.process.application.dto.RejectTaskRequest;
import io.github.illuseahashmap.workflow.process.application.dto.TaskView;
import io.github.illuseahashmap.workflow.process.application.dto.TransferTaskRequest;
import io.github.illuseahashmap.workflow.process.application.port.ParticipantDirectory;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Flowable task lookup, target validation and presentation support. Keeping
 * these adapters outside the runtime application service makes command
 * orchestration and query assembly independently testable.
 */
public final class WorkflowTaskOperationSupport {

    private static final String ASSIGNEE_SUFFIX = "_assignee";
    private static final String ASSIGNEE_LIST_SUFFIX = "_assigneeList";

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final RepositoryService repositoryService;
    private final WorkflowDefinitionService definitionService;
    private final TaskViewAssembler taskViewAssembler;
    private final ParticipantDirectory participantDirectory;
    private final TenantProvider tenantProvider;

    public WorkflowTaskOperationSupport(
            RuntimeService runtimeService,
            TaskService taskService,
            RepositoryService repositoryService,
            WorkflowDefinitionService definitionService,
            TaskViewAssembler taskViewAssembler,
            ParticipantDirectory participantDirectory,
            TenantProvider tenantProvider) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.repositoryService = repositoryService;
        this.definitionService = definitionService;
        this.taskViewAssembler = taskViewAssembler;
        this.participantDirectory = participantDirectory;
        this.tenantProvider = tenantProvider;
    }

    public String resolveProcessDefinitionId(
            String processDefinitionKey, String requestedDefinitionId, String tenantId) {
        if (!StringUtils.hasText(processDefinitionKey)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Process definition key is required");
        }
        String normalizedKey = processDefinitionKey.trim();
        if (StringUtils.hasText(requestedDefinitionId)) {
            String definitionId = requestedDefinitionId.trim();
            var definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionId(definitionId).singleResult();
            if (definition == null || !Objects.equals(tenantId, definition.getTenantId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist");
            }
            if (!Objects.equals(normalizedKey, definition.getKey())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "Process definition id does not match the requested process definition key");
            }
            return definitionId;
        }
        return definitionService.getActiveVersion(normalizedKey).processDefinitionId();
    }

    public Task activeTask(String taskId) {
        Task task = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Active task does not exist");
        }
        assertTaskTenant(task);
        return task;
    }

    public Task activeTaskForOperation(String taskId, io.github.illuseahashmap.workflow.shared.context.CurrentPrincipal actor) {
        Task task = activeTask(taskId);
        boolean servicePrincipal = "SERVICE".equals(actor.principalType());
        if (!servicePrincipal && !taskViewAssembler.canOperate(task, actor.username())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Current assignee cannot operate this task");
        }
        return task;
    }

    public Map<String, Object> processVariablesWithClientOverrides(
            String processInstanceId, Map<String, Object> clientVariables) {
        Map<String, Object> variables = new HashMap<>(runtimeService.getVariables(processInstanceId));
        variables.putAll(ProcessVariablePolicy.clientVariables(clientVariables));
        return variables;
    }

    public String resolveRejectTarget(Task task, String requestedTarget) {
        String target = StringUtils.hasText(requestedTarget)
                ? requestedTarget.trim() : findFirstUserTaskId(task.getProcessDefinitionId());
        if (!StringUtils.hasText(target) || findUserTask(task.getProcessDefinitionId(), target) == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Reject target user task does not exist");
        }
        return target;
    }

    public Map<String, Object> buildRejectVariables(
            RejectTaskRequest request, String processDefinitionId, String targetActivityId) {
        Map<String, Object> variables = new HashMap<>(ProcessVariablePolicy.clientVariables(request.variables()));
        List<String> targetAssignees = normalizeList(request.targetAssignees());
        if (!targetAssignees.isEmpty()) {
            participantDirectory.requireUsableUsernames(targetAssignees);
            UserTask targetTask = findUserTask(processDefinitionId, targetActivityId);
            boolean collection = targetTask != null && (targetTask.getLoopCharacteristics() != null
                    || !CollectionUtils.isEmpty(targetTask.getCandidateUsers())
                    || !CollectionUtils.isEmpty(targetTask.getCandidateGroups()));
            if (!collection && targetAssignees.size() != 1) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "Activity " + targetActivityId + " requires exactly one assignee");
            }
            variables.put(targetActivityId + (collection ? ASSIGNEE_LIST_SUFFIX : ASSIGNEE_SUFFIX),
                    collection ? targetAssignees : targetAssignees.getFirst());
        }
        if (!normalizeList(request.targetCandidateGroups()).isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Candidate group assignment is not supported");
        }
        return variables;
    }

    public void validateTransferTarget(TransferTaskRequest request) {
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
                        .map(value -> value.toLowerCase(java.util.Locale.ROOT)).toList();
        participantDirectory.requireUsableUsernames(targetUsers);
    }

    public void applyTransferTarget(String taskId, TransferTaskRequest request) {
        if (StringUtils.hasText(request.targetAssignee())) {
            taskService.setAssignee(taskId, request.targetAssignee().trim().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        taskService.setAssignee(taskId, null);
        normalizeList(request.targetCandidateUsers()).stream()
                .map(user -> user.toLowerCase(java.util.Locale.ROOT))
                .forEach(user -> taskService.addCandidateUser(taskId, user));
    }

    public void addComment(Task task, String type, String comment) {
        if (StringUtils.hasText(comment)) {
            taskService.addComment(task.getId(), task.getProcessInstanceId(), type, comment.trim());
        }
    }

    public void addTransferComment(
            Task task, TransferTaskRequest request, String previousAssignee, String operator) {
        String target = StringUtils.hasText(request.targetAssignee())
                ? "assignee:" + request.targetAssignee().trim()
                : !normalizeList(request.targetCandidateUsers()).isEmpty()
                    ? "candidateUsers:" + String.join("|", normalizeList(request.targetCandidateUsers()))
                    : "candidateGroups:" + String.join("|", normalizeList(request.targetCandidateGroups()));
        String message = "from=" + Objects.toString(previousAssignee, "") + ", to=" + target
                + ", operator=" + operator
                + (StringUtils.hasText(request.comment()) ? ", comment=" + request.comment().trim() : "");
        taskService.addComment(task.getId(), task.getProcessInstanceId(), "transfer", message);
    }

    public ApproveTaskResult buildApproveResult(String completedTaskId, String processInstanceId) {
        List<TaskView> nextTasks = activeTasks(processInstanceId);
        return new ApproveTaskResult(completedTaskId, processInstanceId, nextTasks.isEmpty(), nextTasks);
    }

    public List<Task> activeTaskEntities(String processInstanceId) {
        return taskService.createTaskQuery().processInstanceId(processInstanceId).active()
                .orderByTaskCreateTime().asc().list();
    }

    public List<TaskView> activeTasks(String processInstanceId) {
        return activeTaskEntities(processInstanceId).stream()
                .peek(this::assertTaskTenant)
                .map(taskViewAssembler::fromActiveTask)
                .toList();
    }

    public void clearCandidates(String taskId) {
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

    private String findFirstUserTaskId(String processDefinitionId) {
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
        if (model == null || model.getMainProcess() == null) {
            return null;
        }
        return model.getMainProcess().getFlowElements().stream()
                .filter(UserTask.class::isInstance).map(FlowElement::getId).findFirst().orElse(null);
    }

    private UserTask findUserTask(String processDefinitionId, String activityId) {
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
        if (model == null || model.getMainProcess() == null) {
            return null;
        }
        FlowElement element = model.getMainProcess().getFlowElement(activityId, true);
        return element instanceof UserTask userTask ? userTask : null;
    }

    private List<String> normalizeList(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(StringUtils::hasText).map(String::trim).distinct().toList();
    }

    public void assertTaskTenant(Task task) {
        assertTenant(task.getTenantId());
    }

    public void assertTenant(String tenantId) {
        if (!tenantProvider.current().tenantId().equals(tenantId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Cross-tenant workflow access is forbidden");
        }
    }
}
