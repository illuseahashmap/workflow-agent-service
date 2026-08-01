package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentTargetType;
import io.github.illuseahashmap.workflow.assignment.domain.NodeAssignmentRule;
import io.github.illuseahashmap.workflow.process.application.WorkflowAdministrationService;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionDiagramView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDefinitionSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessDiagramDataView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceDetailView;
import io.github.illuseahashmap.workflow.process.application.dto.ProcessInstanceSummaryView;
import io.github.illuseahashmap.workflow.process.application.dto.TerminateProcessRequest;
import io.github.illuseahashmap.workflow.process.application.port.ProcessInstanceLock;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.image.ProcessDiagramGenerator;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.flowable.variable.api.persistence.entity.VariableInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class WorkflowAdministrationServiceImpl implements WorkflowAdministrationService {

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final TaskService taskService;
    private final ProcessEngine processEngine;
    private final JdbcTemplate jdbcTemplate;
    private final ProcessInstanceLock processInstanceLock;
    private final AssignmentRuleService assignmentRuleService;

    public WorkflowAdministrationServiceImpl(RepositoryService repositoryService,
                                             RuntimeService runtimeService,
                                             HistoryService historyService,
                                             TaskService taskService,
                                             ProcessEngine processEngine,
                                             JdbcTemplate jdbcTemplate,
                                             ProcessInstanceLock processInstanceLock,
                                             AssignmentRuleService assignmentRuleService) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.taskService = taskService;
        this.processEngine = processEngine;
        this.jdbcTemplate = jdbcTemplate;
        this.processInstanceLock = processInstanceLock;
        this.assignmentRuleService = assignmentRuleService;
    }

    @Override
    public byte[] generateProcessDiagram(String processInstanceId) {
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
                "png",
                highlighted,
                List.of(),
                "Arial",
                "Arial",
                "Arial",
                null,
                1.0,
                true)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "Process diagram generation failed");
        }
    }

    @Override
    public ProcessDiagramDataView getProcessDiagramData(String processInstanceId) {
        ProcessInstanceDescriptor descriptor = resolveProcessInstance(processInstanceId);
        List<HistoricActivityInstance> activities = historicActivities(processInstanceId);
        List<String> completed = activities.stream()
                .filter(activity -> activity.getEndTime() != null)
                .map(HistoricActivityInstance::getActivityId)
                .distinct()
                .toList();
        BpmnModel bpmnModel = repositoryService.getBpmnModel(descriptor.processDefinitionId());
        return new ProcessDiagramDataView(
                readBpmnXml(descriptor.processDefinitionId()),
                completed,
                descriptor.activeActivityIds(),
                highlightedFlows(bpmnModel, activities),
                activityDetails(processInstanceId, activities));
    }

    @Override
    public PageResult<ProcessDefinitionSummaryView> pageProcessDefinitions(
            Integer pageNum, Integer pageSize, String key, String name, String publishStatus) {
        int normalizedPage = normalizePage(pageNum);
        int normalizedSize = normalizeSize(pageSize);
        String tenantId = TenantContext.current().tenantId();
        var query = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId)
                .latestVersion();
        if (StringUtils.hasText(key)) {
            query.processDefinitionKeyLike("%" + key.trim() + "%");
        }
        if (StringUtils.hasText(name)) {
            query.processDefinitionNameLike("%" + name.trim() + "%");
        }
        Map<String, ActiveVersionRow> activeVersions = activeVersions(tenantId);
        String normalizedStatus = normalizeStatus(publishStatus, "all", "published", "unpublished");
        List<ProcessDefinitionSummaryView> summaries = query.list().stream()
                .map(definition -> toDefinitionSummary(definition, activeVersions.get(definition.getKey())))
                .filter(summary -> "all".equals(normalizedStatus)
                        || normalizedStatus.equals(summary.publishStatus()))
                .sorted(java.util.Comparator.comparing(
                        ProcessDefinitionSummaryView::latestDeployTime,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
        return slice(summaries, normalizedPage, normalizedSize);
    }

    @Override
    public PageResult<ProcessInstanceSummaryView> pageProcessInstances(
            Integer pageNum, Integer pageSize, String definitionKey, String definitionName,
            String processInstanceId, String businessKey, String status) {
        int normalizedPage = normalizePage(pageNum);
        int normalizedSize = normalizeSize(pageSize);
        int firstResult = (normalizedPage - 1) * normalizedSize;
        String tenantId = TenantContext.current().tenantId();
        HistoricProcessInstanceQuery countQuery = buildInstanceQuery(
                tenantId, definitionKey, definitionName, processInstanceId, businessKey, status);
        long total = countQuery.count();
        List<HistoricProcessInstance> instances = buildInstanceQuery(
                tenantId, definitionKey, definitionName, processInstanceId, businessKey, status)
                .orderByProcessInstanceStartTime()
                .desc()
                .listPage(firstResult, normalizedSize);
        Map<String, ProcessDefinition> definitions = new HashMap<>();
        List<ProcessInstanceSummaryView> records = instances.stream()
                .map(instance -> toInstanceSummary(instance, definitions))
                .toList();
        return new PageResult<>(total, normalizedPage, normalizedSize, records);
    }

    @Override
    public ProcessInstanceDetailView getProcessInstanceDetail(String processInstanceId) {
        HistoricProcessInstance instance = requireHistoricInstance(processInstanceId);
        Map<String, Object> variableMap = historicVariableMap(processInstanceId);
        List<ProcessInstanceDetailView.TaskItem> tasks = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricTaskInstanceStartTime()
                .asc()
                .list()
                .stream()
                .map(task -> toTaskItem(task, instance.getProcessDefinitionId(), variableMap))
                .toList();
        List<ProcessInstanceDetailView.VariableItem> variables = queryVariables(processInstanceId);
        return new ProcessInstanceDetailView(toInstanceSummary(instance, new HashMap<>()), tasks, variables);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void terminateProcessInstance(TerminateProcessRequest request) {
        String processInstanceId = request.processInstanceId().trim();
        processInstanceLock.execute(processInstanceId, () -> {
            String tenantId = TenantContext.current().tenantId();
            ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                    .processInstanceTenantId(tenantId)
                    .processInstanceId(processInstanceId)
                    .singleResult();
            if (instance == null) {
                if (historyService.createHistoricProcessInstanceQuery()
                        .processInstanceTenantId(tenantId)
                        .processInstanceId(processInstanceId)
                        .count() > 0) {
                    throw new BusinessException(ErrorCode.CONFLICT, "Process instance is already finished");
                }
                throw new BusinessException(ErrorCode.NOT_FOUND, "Process instance does not exist");
            }
            runtimeService.deleteProcessInstance(processInstanceId, request.reason().trim());
            return null;
        });
    }

    @Override
    public ProcessDefinitionDiagramView getProcessDefinitionDiagram(
            String processDefinitionKey, Integer version, String processDefinitionId) {
        String tenantId = TenantContext.current().tenantId();
        ProcessDefinition definition = resolveDiagramDefinition(
                tenantId, processDefinitionKey, version, processDefinitionId);
        ActiveVersionRow activeVersion = activeVersions(tenantId).get(definition.getKey());
        return new ProcessDefinitionDiagramView(
                definition.getId(),
                definition.getKey(),
                definition.getName(),
                definition.getVersion(),
                definition.getDeploymentId(),
                deploymentTime(definition.getDeploymentId()),
                activeVersion != null && definition.getId().equals(activeVersion.processDefinitionId()),
                readBpmnXml(definition),
                definition.getTenantId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProcessDefinitions(String processDefinitionKey) {
        String tenantId = TenantContext.current().tenantId();
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId)
                .processDefinitionKey(processDefinitionKey)
                .list();
        if (definitions.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist");
        }
        definitions.stream().map(ProcessDefinition::getDeploymentId).distinct()
                .forEach(deploymentId -> repositoryService.deleteDeployment(deploymentId, true));
        jdbcTemplate.update("""
                DELETE FROM workflow_active_version WHERE tenant_id = ? AND process_definition_key = ?
                """, tenantId, processDefinitionKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProcessDefinitionVersion(String processDefinitionKey, Integer version) {
        if (version == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Process definition version is required");
        }
        String tenantId = TenantContext.current().tenantId();
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenantId)
                .processDefinitionKey(processDefinitionKey)
                .processDefinitionVersion(version)
                .singleResult();
        if (definition == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition version does not exist");
        }
        repositoryService.deleteDeployment(definition.getDeploymentId(), true);
        jdbcTemplate.update("""
                DELETE FROM workflow_active_version
                WHERE tenant_id = ? AND process_definition_key = ? AND process_definition_id = ?
                """, tenantId, processDefinitionKey, definition.getId());
    }

    private ProcessInstanceDescriptor resolveProcessInstance(String processInstanceId) {
        String tenantId = TenantContext.current().tenantId();
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
                .processInstanceTenantId(TenantContext.current().tenantId())
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
                .orderByHistoricActivityInstanceStartTime()
                .asc()
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
                .processInstanceId(processInstanceId)
                .list()
                .stream()
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
                    candidateUsers, candidateGroups, toOffsetDateTime(task.getCreateTime()),
                    null, null, null));
        }
        return Map.copyOf(details);
    }

    private HistoricProcessInstanceQuery buildInstanceQuery(
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
        String normalizedStatus = normalizeStatus(status, "all", "running", "finished");
        if ("running".equals(normalizedStatus)) {
            query.unfinished();
        } else if ("finished".equals(normalizedStatus)) {
            query.finished();
        }
        return query;
    }

    private ProcessDefinitionSummaryView toDefinitionSummary(ProcessDefinition definition, ActiveVersionRow active) {
        ProcessDefinition activeDefinition = active == null ? null : repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(active.processDefinitionId())
                .singleResult();
        return new ProcessDefinitionSummaryView(
                definition.getId(), definition.getKey(), definition.getName(), definition.getVersion(),
                definition.getDeploymentId(), deploymentTime(definition.getDeploymentId()),
                active == null ? null : active.version(), active == null ? null : active.processDefinitionId(),
                activeDefinition == null ? null : activeDefinition.getDeploymentId(),
                active == null ? null : active.activatedAt(),
                active == null ? "unpublished" : "published", definition.getTenantId());
    }

    private ProcessInstanceSummaryView toInstanceSummary(
            HistoricProcessInstance instance, Map<String, ProcessDefinition> definitionCache) {
        ProcessDefinition definition = definitionCache.computeIfAbsent(
                instance.getProcessDefinitionId(),
                id -> repositoryService.createProcessDefinitionQuery().processDefinitionId(id).singleResult());
        List<HistoricTaskInstance> tasks = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(instance.getId())
                .orderByHistoricTaskInstanceStartTime()
                .desc()
                .listPage(0, 1);
        String latestTaskId = tasks.isEmpty() ? null : tasks.getFirst().getId();
        Date lastUpdateTime = tasks.isEmpty()
                ? (instance.getEndTime() == null ? instance.getStartTime() : instance.getEndTime())
                : (tasks.getFirst().getEndTime() == null
                    ? tasks.getFirst().getStartTime()
                    : tasks.getFirst().getEndTime());
        String status = instance.getEndTime() == null ? "RUNNING"
                : StringUtils.hasText(instance.getDeleteReason()) ? "TERMINATED" : "FINISHED";
        return new ProcessInstanceSummaryView(
                instance.getId(), instance.getProcessDefinitionId(),
                definition == null ? null : definition.getKey(),
                definition == null ? null : definition.getName(),
                definition == null ? null : definition.getVersion(),
                latestTaskId, instance.getBusinessKey(), instance.getStartUserId(),
                toOffsetDateTime(instance.getStartTime()), toOffsetDateTime(instance.getEndTime()),
                toOffsetDateTime(lastUpdateTime), instance.getDurationInMillis(),
                instance.getDeleteReason(), status, instance.getTenantId());
    }

    private ProcessInstanceDetailView.TaskItem toTaskItem(HistoricTaskInstance task,
                                                          String processDefinitionId,
                                                          Map<String, Object> variables) {
        String assignee = task.getAssignee();
        if (!StringUtils.hasText(assignee)) {
            NodeAssignmentRule rule = assignmentRuleService.match(
                    Objects.toString(variables.getOrDefault("tenantId", TenantContext.current().tenantId())),
                    processDefinitionId,
                    task.getTaskDefinitionKey(),
                    variables);
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
                candidateUsers, candidateGroups, status,
                task.getDeleteReason(), toOffsetDateTime(task.getStartTime()),
                toOffsetDateTime(task.getEndTime()), task.getDurationInMillis());
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

    private Map<String, Object> historicVariableMap(String processInstanceId) {
        return historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId)
                .list()
                .stream()
                .collect(Collectors.toMap(
                        HistoricVariableInstance::getVariableName,
                        HistoricVariableInstance::getValue,
                        (existing, replacement) -> replacement));
    }

    private List<ProcessInstanceDetailView.VariableItem> queryVariables(String processInstanceId) {
        Map<String, ProcessInstanceDetailView.VariableItem> variables = new HashMap<>();
        historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId)
                .list()
                .stream()
                .map(this::toVariableItem)
                .forEach(variable -> variables.put(variableKey(variable), variable));
        if (runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).count() > 0) {
            runtimeService.createVariableInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .list()
                    .stream()
                    .map(this::toVariableItem)
                    .forEach(variable -> variables.put(variableKey(variable), variable));
        }
        return variables.values().stream()
                .sorted(java.util.Comparator.comparing(
                        ProcessInstanceDetailView.VariableItem::variableName,
                        java.util.Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    private String variableKey(ProcessInstanceDetailView.VariableItem variable) {
        return String.join("|",
                Objects.toString(variable.variableName(), ""),
                Objects.toString(variable.executionId(), ""),
                Objects.toString(variable.taskId(), ""),
                Objects.toString(variable.scopeId(), ""),
                Objects.toString(variable.subScopeId(), ""));
    }

    private ProcessDefinition resolveDiagramDefinition(
            String tenantId, String key, Integer version, String processDefinitionId) {
        ProcessDefinition definition;
        if (StringUtils.hasText(processDefinitionId)) {
            definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionTenantId(tenantId)
                    .processDefinitionId(processDefinitionId.trim())
                    .singleResult();
            if (definition != null && StringUtils.hasText(key) && !key.trim().equals(definition.getKey())) {
                definition = null;
            }
        } else if (version != null) {
            definition = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionTenantId(tenantId)
                    .processDefinitionKey(key)
                    .processDefinitionVersion(version)
                    .singleResult();
        } else {
            ActiveVersionRow active = activeVersions(tenantId).get(key);
            definition = active == null ? null : repositoryService.createProcessDefinitionQuery()
                    .processDefinitionTenantId(tenantId)
                    .processDefinitionId(active.processDefinitionId())
                    .singleResult();
        }
        if (definition == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist");
        }
        return definition;
    }

    private Map<String, ActiveVersionRow> activeVersions(String tenantId) {
        return jdbcTemplate.query("""
                SELECT process_definition_key, process_definition_id, version, activated_at
                FROM workflow_active_version WHERE tenant_id = ?
                """, (resultSet, rowNumber) -> new ActiveVersionRow(
                resultSet.getString("process_definition_key"),
                resultSet.getString("process_definition_id"),
                resultSet.getInt("version"),
                resultSet.getObject("activated_at", OffsetDateTime.class)), tenantId)
                .stream()
                .collect(Collectors.toMap(ActiveVersionRow::processDefinitionKey, Function.identity()));
    }

    private String readBpmnXml(String processDefinitionId) {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId)
                .singleResult();
        if (definition == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist");
        }
        return readBpmnXml(definition);
    }

    private String readBpmnXml(ProcessDefinition definition) {
        try (InputStream input = repositoryService.getResourceAsStream(
                definition.getDeploymentId(), definition.getResourceName())) {
            if (input == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "BPMN XML resource does not exist");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.WORKFLOW_ERROR, "BPMN XML cannot be read");
        }
    }

    private OffsetDateTime deploymentTime(String deploymentId) {
        Deployment deployment = repositoryService.createDeploymentQuery().deploymentId(deploymentId).singleResult();
        return deployment == null ? null : toOffsetDateTime(deployment.getDeploymentTime());
    }

    private OffsetDateTime toOffsetDateTime(Date date) {
        return date == null ? null : date.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private int normalizePage(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private int normalizeSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
    }

    private String normalizeStatus(String value, String... supported) {
        String normalized = StringUtils.hasText(value) ? value.trim().toLowerCase(java.util.Locale.ROOT) : supported[0];
        if (java.util.Arrays.stream(supported).noneMatch(normalized::equals)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported status: " + value);
        }
        return normalized;
    }

    private <T> PageResult<T> slice(List<T> source, int pageNum, int pageSize) {
        int fromIndex = Math.min((pageNum - 1) * pageSize, source.size());
        int toIndex = Math.min(fromIndex + pageSize, source.size());
        return new PageResult<>(source.size(), pageNum, pageSize, source.subList(fromIndex, toIndex));
    }

    private record ProcessInstanceDescriptor(String processDefinitionId, List<String> activeActivityIds) {
    }

    private record ActiveVersionRow(
            String processDefinitionKey,
            String processDefinitionId,
            int version,
            OffsetDateTime activatedAt
    ) {
    }
}
