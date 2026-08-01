package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentTargetType;
import io.github.illuseahashmap.workflow.assignment.domain.NodeAssignmentRule;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDiagramDataView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceDetailView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceSummaryView;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.image.ProcessDiagramGenerator;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.flowable.variable.api.persistence.entity.VariableInstance;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class WorkflowInstanceReadService {

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final TaskService taskService;
    private final ProcessEngine processEngine;
    private final AssignmentRuleService assignmentRuleService;
    private final WorkflowDefinitionReadService definitionReadService;
    private final TenantProvider tenantProvider;

    WorkflowInstanceReadService(RepositoryService repositoryService,
                                RuntimeService runtimeService,
                                HistoryService historyService,
                                TaskService taskService,
                                ProcessEngine processEngine,
                                AssignmentRuleService assignmentRuleService,
                                WorkflowDefinitionReadService definitionReadService,
                                TenantProvider tenantProvider) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.taskService = taskService;
        this.processEngine = processEngine;
        this.assignmentRuleService = assignmentRuleService;
        this.definitionReadService = definitionReadService;
        this.tenantProvider = tenantProvider;
    }

    byte[] generateDiagram(String processInstanceId) {
        ProcessInstanceDescriptor descriptor = resolveProcessInstance(processInstanceId);
        List<String> completed = historicActivities(processInstanceId).stream()
                .filter(activity -> activity.getEndTime() != null)
                .map(HistoricActivityInstance::getActivityId)
                .toList();
        List<String> highlighted = new ArrayList<>(completed);
        highlighted.addAll(descriptor.activeActivityIds());
        ProcessDiagramGenerator generator = processEngine.getProcessEngineConfiguration().getProcessDiagramGenerator();
        try (InputStream input = generator.generateDiagram(
                repositoryService.getBpmnModel(descriptor.processDefinitionId()),
                "png", highlighted, List.of(), "Arial", "Arial", "Arial", null, 1.0, true)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "Process diagram generation failed");
        }
    }

    ProcessDiagramDataView diagramData(String processInstanceId) {
        ProcessInstanceDescriptor descriptor = resolveProcessInstance(processInstanceId);
        List<HistoricActivityInstance> activities = historicActivities(processInstanceId);
        List<String> completed = activities.stream()
                .filter(activity -> activity.getEndTime() != null)
                .map(HistoricActivityInstance::getActivityId)
                .distinct()
                .toList();
        BpmnModel bpmnModel = repositoryService.getBpmnModel(descriptor.processDefinitionId());
        return new ProcessDiagramDataView(
                definitionReadService.readBpmnXml(descriptor.processDefinitionId()),
                completed,
                descriptor.activeActivityIds(),
                highlightedFlows(bpmnModel, activities),
                activityDetails(processInstanceId, activities));
    }

    PageResult<ProcessInstanceSummaryView> page(
            Integer pageNum, Integer pageSize, String definitionKey, String definitionName,
            String processInstanceId, String businessKey, String status) {
        int normalizedPage = normalizePage(pageNum);
        int normalizedSize = normalizeSize(pageSize);
        int firstResult = (normalizedPage - 1) * normalizedSize;
        String tenantId = tenantProvider.current().tenantId();
        long total = buildQuery(tenantId, definitionKey, definitionName, processInstanceId, businessKey, status).count();
        List<HistoricProcessInstance> instances = buildQuery(
                tenantId, definitionKey, definitionName, processInstanceId, businessKey, status)
                .orderByProcessInstanceStartTime().desc()
                .listPage(firstResult, normalizedSize);
        Set<String> definitionIds = instances.stream().map(HistoricProcessInstance::getProcessDefinitionId)
                .collect(Collectors.toSet());
        Map<String, ProcessDefinition> definitions = definitionIds.isEmpty() ? Map.of()
                : repositoryService.createProcessDefinitionQuery().processDefinitionIds(definitionIds).list().stream()
                        .collect(Collectors.toMap(ProcessDefinition::getId, Function.identity()));
        Map<String, HistoricTaskInstance> latestTasks = latestTasks(instances);
        List<ProcessInstanceSummaryView> records = instances.stream()
                .map(instance -> toSummary(instance, definitions, latestTasks.get(instance.getId())))
                .toList();
        return new PageResult<>(total, normalizedPage, normalizedSize, records);
    }

    ProcessInstanceDetailView detail(String processInstanceId) {
        HistoricProcessInstance instance = requireHistoricInstance(processInstanceId);
        Map<String, Object> variableMap = historicVariableMap(processInstanceId);
        List<ProcessInstanceDetailView.TaskItem> tasks = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricTaskInstanceStartTime().asc()
                .list().stream()
                .map(task -> toTaskItem(task, instance.getProcessDefinitionId(), variableMap))
                .toList();
        Map<String, ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                .processDefinitionIds(Set.of(instance.getProcessDefinitionId())).list().stream()
                .collect(Collectors.toMap(ProcessDefinition::getId, Function.identity()));
        HistoricTaskInstance latestTask = tasksForInstance(processInstanceId).stream().findFirst().orElse(null);
        return new ProcessInstanceDetailView(
                toSummary(instance, definitions, latestTask), tasks, queryVariables(processInstanceId));
    }

    private Map<String, HistoricTaskInstance> latestTasks(List<HistoricProcessInstance> instances) {
        List<String> ids = instances.stream().map(HistoricProcessInstance::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return historyService.createHistoricTaskInstanceQuery().processInstanceIdIn(ids).list().stream()
                .collect(Collectors.toMap(HistoricTaskInstance::getProcessInstanceId, Function.identity(),
                        (left, right) -> taskTimestamp(left).compareTo(taskTimestamp(right)) >= 0 ? left : right));
    }

    private List<HistoricTaskInstance> tasksForInstance(String processInstanceId) {
        return historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricTaskInstanceStartTime().desc()
                .listPage(0, 1);
    }

    private Date taskTimestamp(HistoricTaskInstance task) {
        return task.getEndTime() == null ? task.getStartTime() : task.getEndTime();
    }

    private ProcessInstanceDescriptor resolveProcessInstance(String processInstanceId) {
        String tenantId = tenantProvider.current().tenantId();
        ProcessInstance running = runtimeService.createProcessInstanceQuery()
                .processInstanceTenantId(tenantId)
                .processInstanceId(processInstanceId)
                .singleResult();
        if (running != null) {
            return new ProcessInstanceDescriptor(
                    running.getProcessDefinitionId(), runtimeService.getActiveActivityIds(processInstanceId));
        }
        HistoricProcessInstance historic = requireHistoricInstance(processInstanceId);
        return new ProcessInstanceDescriptor(historic.getProcessDefinitionId(), List.of());
    }

    private HistoricProcessInstance requireHistoricInstance(String processInstanceId) {
        HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceTenantId(tenantProvider.current().tenantId())
                .processInstanceId(processInstanceId)
                .singleResult();
        if (instance == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process instance does not exist");
        }
        return instance;
    }

    private List<HistoricActivityInstance> historicActivities(String processInstanceId) {
        return historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricActivityInstanceStartTime().asc()
                .list();
    }

    private List<String> highlightedFlows(BpmnModel model, List<HistoricActivityInstance> activities) {
        Set<String> flows = new HashSet<>();
        for (int index = 0; index < activities.size() - 1; index++) {
            String source = activities.get(index).getActivityId();
            String target = activities.get(index + 1).getActivityId();
            for (FlowElement element : model.getMainProcess().getFlowElements()) {
                if (element instanceof SequenceFlow flow
                        && source.equals(flow.getSourceRef())
                        && target.equals(flow.getTargetRef())) {
                    flows.add(flow.getId());
                }
            }
        }
        return List.copyOf(flows);
    }

    private Map<String, ProcessDiagramDataView.ActivityDetail> activityDetails(
            String processInstanceId, List<HistoricActivityInstance> activities) {
        Map<String, List<HistoricTaskInstance>> taskMap = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId).list().stream()
                .collect(Collectors.groupingBy(HistoricTaskInstance::getTaskDefinitionKey));
        Map<String, ProcessDiagramDataView.ActivityDetail> details = new LinkedHashMap<>();
        for (HistoricActivityInstance activity : activities) {
            List<HistoricTaskInstance> tasks = taskMap.getOrDefault(activity.getActivityId(), List.of());
            String assignee = tasks.stream().map(HistoricTaskInstance::getAssignee)
                    .filter(StringUtils::hasText).distinct().collect(Collectors.joining(","));
            String deleteReason = tasks.stream().map(HistoricTaskInstance::getDeleteReason)
                    .filter(StringUtils::hasText).reduce((first, second) -> second).orElse(null);
            details.put(activity.getActivityId(), new ProcessDiagramDataView.ActivityDetail(
                    activity.getActivityId(), activity.getActivityName(), activity.getActivityType(),
                    StringUtils.hasText(assignee) ? assignee : activity.getAssignee(),
                    List.of(), List.of(), toOffsetDateTime(activity.getStartTime()),
                    toOffsetDateTime(activity.getEndTime()), activity.getDurationInMillis(), deleteReason));
        }
        for (Task task : taskService.createTaskQuery().processInstanceId(processInstanceId).active().list()) {
            List<org.flowable.identitylink.api.IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
            List<String> candidateUsers = links.stream().map(org.flowable.identitylink.api.IdentityLink::getUserId)
                    .filter(Objects::nonNull).toList();
            List<String> candidateGroups = links.stream().map(org.flowable.identitylink.api.IdentityLink::getGroupId)
                    .filter(Objects::nonNull).toList();
            details.put(task.getTaskDefinitionKey(), new ProcessDiagramDataView.ActivityDetail(
                    task.getTaskDefinitionKey(), task.getName(), "userTask", task.getAssignee(),
                    candidateUsers, candidateGroups, toOffsetDateTime(task.getCreateTime()), null, null, null));
        }
        return Map.copyOf(details);
    }

    private HistoricProcessInstanceQuery buildQuery(
            String tenantId, String definitionKey, String definitionName,
            String processInstanceId, String businessKey, String status) {
        HistoricProcessInstanceQuery query = historyService.createHistoricProcessInstanceQuery()
                .processInstanceTenantId(tenantId);
        if (StringUtils.hasText(definitionKey)) {
            query.processDefinitionKeyLike("%" + definitionKey.trim() + "%");
        }
        if (StringUtils.hasText(definitionName)) {
            query.processDefinitionNameLike("%" + definitionName.trim() + "%");
        }
        if (StringUtils.hasText(processInstanceId)) {
            query.processInstanceId(processInstanceId.trim());
        }
        if (StringUtils.hasText(businessKey)) {
            query.processInstanceBusinessKeyLike("%" + businessKey.trim() + "%");
        }
        String normalizedStatus = normalizeStatus(status);
        if ("running".equals(normalizedStatus)) {
            query.unfinished();
        } else if ("finished".equals(normalizedStatus)) {
            query.finished();
        }
        return query;
    }

    private ProcessInstanceSummaryView toSummary(
            HistoricProcessInstance instance,
            Map<String, ProcessDefinition> definitions,
            HistoricTaskInstance latestTask) {
        ProcessDefinition definition = definitions.get(instance.getProcessDefinitionId());
        Date lastUpdateTime = latestTask == null
                ? (instance.getEndTime() == null ? instance.getStartTime() : instance.getEndTime())
                : taskTimestamp(latestTask);
        String status = instance.getEndTime() == null ? "RUNNING"
                : StringUtils.hasText(instance.getDeleteReason()) ? "TERMINATED" : "FINISHED";
        return new ProcessInstanceSummaryView(
                instance.getId(), instance.getProcessDefinitionId(),
                definition == null ? null : definition.getKey(),
                definition == null ? null : definition.getName(),
                definition == null ? null : definition.getVersion(),
                latestTask == null ? null : latestTask.getId(), instance.getBusinessKey(), instance.getStartUserId(),
                toOffsetDateTime(instance.getStartTime()), toOffsetDateTime(instance.getEndTime()),
                toOffsetDateTime(lastUpdateTime), instance.getDurationInMillis(),
                instance.getDeleteReason(), status, instance.getTenantId());
    }

    private ProcessInstanceDetailView.TaskItem toTaskItem(
            HistoricTaskInstance task, String processDefinitionId, Map<String, Object> variables) {
        String assignee = task.getAssignee();
        if (!StringUtils.hasText(assignee)) {
            NodeAssignmentRule rule = assignmentRuleService.match(
                    Objects.toString(variables.getOrDefault(
                            "tenantId", tenantProvider.current().tenantId())),
                    processDefinitionId, task.getTaskDefinitionKey(), variables);
            if (rule != null) {
                List<String> configuredAssignees = rule.targetValues(AssignmentTargetType.ASSIGNEE);
                if (configuredAssignees.isEmpty()) {
                    configuredAssignees = rule.targetValues(AssignmentTargetType.COUNTERSIGN_USER);
                }
                assignee = String.join(",", configuredAssignees);
            }
        }
        List<String> candidateUsers = List.of();
        List<String> candidateGroups = List.of();
        if (task.getEndTime() == null) {
            List<org.flowable.identitylink.api.IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
            candidateUsers = links.stream().map(org.flowable.identitylink.api.IdentityLink::getUserId)
                    .filter(Objects::nonNull).toList();
            candidateGroups = links.stream().map(org.flowable.identitylink.api.IdentityLink::getGroupId)
                    .filter(Objects::nonNull).toList();
        }
        String status = task.getEndTime() == null ? "RUNNING"
                : !StringUtils.hasText(task.getDeleteReason())
                    || "completed".equalsIgnoreCase(task.getDeleteReason()) ? "COMPLETED" : "DELETED";
        return new ProcessInstanceDetailView.TaskItem(
                task.getId(), task.getTaskDefinitionKey(), task.getName(), assignee,
                candidateUsers, candidateGroups, status, task.getDeleteReason(),
                toOffsetDateTime(task.getStartTime()), toOffsetDateTime(task.getEndTime()),
                task.getDurationInMillis());
    }

    private Map<String, Object> historicVariableMap(String processInstanceId) {
        return historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId).list().stream()
                .collect(Collectors.toMap(HistoricVariableInstance::getVariableName,
                        HistoricVariableInstance::getValue, (existing, replacement) -> replacement));
    }

    private List<ProcessInstanceDetailView.VariableItem> queryVariables(String processInstanceId) {
        Map<String, ProcessInstanceDetailView.VariableItem> variables = new HashMap<>();
        historyService.createHistoricVariableInstanceQuery().processInstanceId(processInstanceId).list().stream()
                .map(this::toVariableItem)
                .forEach(variable -> variables.put(variableKey(variable), variable));
        if (runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).count() > 0) {
            runtimeService.createVariableInstanceQuery().processInstanceId(processInstanceId).list().stream()
                    .map(this::toVariableItem)
                    .forEach(variable -> variables.put(variableKey(variable), variable));
        }
        return variables.values().stream()
                .sorted(Comparator.comparing(ProcessInstanceDetailView.VariableItem::variableName,
                        Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    private ProcessInstanceDetailView.VariableItem toVariableItem(HistoricVariableInstance variable) {
        return new ProcessInstanceDetailView.VariableItem(
                variable.getId(), variable.getVariableName(), variable.getVariableTypeName(), variable.getValue(),
                variable.getExecutionId(), variable.getTaskId(), toOffsetDateTime(variable.getCreateTime()),
                toOffsetDateTime(variable.getLastUpdatedTime()), variable.getScopeId(),
                variable.getSubScopeId(), variable.getScopeType());
    }

    private ProcessInstanceDetailView.VariableItem toVariableItem(VariableInstance variable) {
        return new ProcessInstanceDetailView.VariableItem(
                variable.getId(), variable.getName(), variable.getTypeName(), variable.getValue(),
                variable.getExecutionId(), variable.getTaskId(), null, null,
                variable.getScopeId(), variable.getSubScopeId(), variable.getScopeType());
    }

    private String variableKey(ProcessInstanceDetailView.VariableItem variable) {
        return String.join("|", Objects.toString(variable.variableName(), ""),
                Objects.toString(variable.executionId(), ""), Objects.toString(variable.taskId(), ""),
                Objects.toString(variable.scopeId(), ""), Objects.toString(variable.subScopeId(), ""));
    }

    private String normalizeStatus(String status) {
        String normalized = StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT) : "all";
        if (!Set.of("all", "running", "finished").contains(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported status: " + status);
        }
        return normalized;
    }

    private int normalizePage(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private int normalizeSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
    }

    private OffsetDateTime toOffsetDateTime(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private record ProcessInstanceDescriptor(String processDefinitionId, List<String> activeActivityIds) {
    }
}
