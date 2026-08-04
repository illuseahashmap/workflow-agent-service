package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;
import io.github.illuseahashmap.workflow.process.application.dto.ParticipantAssignment;
import io.github.illuseahashmap.workflow.process.application.dto.ParticipantRequirementView;
import io.github.illuseahashmap.workflow.process.application.dto.TaskParticipantAction;
import io.github.illuseahashmap.workflow.process.application.port.ParticipantDirectory;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.Gateway;
import org.flowable.bpmn.model.InclusiveGateway;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.common.engine.impl.el.ExpressionManager;
import org.flowable.common.engine.impl.variable.MapDelegateVariableContainer;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Component
public class FlowableParticipantAssignmentCoordinator {

    private static final String ASSIGNEE_SUFFIX = "_assignee";
    private static final String ASSIGNEE_LIST_SUFFIX = "_assigneeList";
    private static final String RESOLVER_NAME = "assigneeService";

    private final RepositoryService repositoryService;
    private final AssignmentRuleService assignmentRuleService;
    private final ParticipantDirectory participantDirectory;
    private final ExpressionManager expressionManager;

    public FlowableParticipantAssignmentCoordinator(RepositoryService repositoryService,
                                                    AssignmentRuleService assignmentRuleService,
                                                    ParticipantDirectory participantDirectory,
                                                    ProcessEngineConfigurationImpl processEngineConfiguration) {
        this.repositoryService = repositoryService;
        this.assignmentRuleService = assignmentRuleService;
        this.participantDirectory = participantDirectory;
        this.expressionManager = processEngineConfiguration.getExpressionManager();
    }

    public List<ParticipantRequirementView> requirementsForStart(
            String tenantId, String processDefinitionId, Map<String, Object> variables) {
        return requirements(tenantId, processDefinitionId,
                firstUserTasks(processDefinitionId, safeVariables(variables)), safeVariables(variables), true);
    }

    public List<ParticipantRequirementView> requirementsForTask(
            String tenantId, Task task, TaskParticipantAction action,
            String targetActivityId, Map<String, Object> variables) {
        return requirements(tenantId, task.getProcessDefinitionId(),
                targetTasks(task, action, targetActivityId, safeVariables(variables)), safeVariables(variables), false);
    }

    public Map<String, Object> prepareForStart(
            String tenantId, String processDefinitionId, Map<String, Object> variables,
            List<ParticipantAssignment> assignments) {
        Map<String, Object> safeVariables = safeVariables(variables);
        return buildTechnicalVariables(
                requirementsForStart(tenantId, processDefinitionId, safeVariables), assignments);
    }

    public Map<String, Object> prepareForTask(
            String tenantId, Task task, TaskParticipantAction action, String targetActivityId,
            Map<String, Object> variables, List<ParticipantAssignment> assignments) {
        return buildTechnicalVariables(
                requirementsForTask(tenantId, task, action, targetActivityId, variables), assignments);
    }

    private List<ParticipantRequirementView> requirements(
            String tenantId, String processDefinitionId,
            List<UserTask> tasks, Map<String, Object> variables,
            boolean includeConfiguredRules) {
        List<ParticipantRequirementView> requirements = new ArrayList<>();
        for (UserTask task : tasks) {
            AssignmentType type = expectedType(task);
            if (!requiresPlatformResolution(task, type)
                    || hasParticipantVariable(variables, task.getId(), type)) {
                continue;
            }
            boolean ruleConfigured = assignmentRuleService.match(
                    tenantId, processDefinitionId, task.getId(), variables) != null;
            if (ruleConfigured && !includeConfiguredRules) {
                continue;
            }
            requirements.add(new ParticipantRequirementView(
                    task.getId(), displayName(task), type,
                    type != AssignmentType.ASSIGNEE, !ruleConfigured));
        }
        return List.copyOf(requirements);
    }

    private Map<String, Object> buildTechnicalVariables(
            List<ParticipantRequirementView> requirements,
            List<ParticipantAssignment> assignments) {
        Map<String, ParticipantAssignment> assignmentByActivity = assignmentMap(assignments);
        Set<String> availableActivities = requirements.stream()
                .map(ParticipantRequirementView::activityId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!availableActivities.containsAll(assignmentByActivity.keySet())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Participant assignments contain an activity that is not currently required");
        }
        Set<String> missingActivities = requirements.stream()
                .filter(ParticipantRequirementView::required)
                .map(ParticipantRequirementView::activityId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        missingActivities.removeAll(assignmentByActivity.keySet());
        if (!missingActivities.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Participants are required for activities: " + String.join(", ", missingActivities));
        }
        List<String> requestedUsernames = assignmentByActivity.values().stream()
                .flatMap(assignment -> normalizeUsers(assignment.usernames()).stream())
                .distinct()
                .toList();
        Set<String> selectableUsernames = requestedUsernames.isEmpty()
                ? Set.of()
                : participantDirectory.validateSelectableUsernames(requestedUsernames);
        Map<String, Object> technicalVariables = new HashMap<>();
        for (ParticipantRequirementView requirement : requirements) {
            ParticipantAssignment assignment = assignmentByActivity.get(requirement.activityId());
            if (assignment == null) {
                continue;
            }
            List<String> users = normalizeUsers(assignment.usernames());
            if (users.isEmpty() || !selectableUsernames.containsAll(users)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "Participants contain unavailable tenant users");
            }
            if (!requirement.multiple() && users.size() != 1) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "Activity " + requirement.activityId() + " requires exactly one participant");
            }
            technicalVariables.put(
                    requirement.activityId()
                            + (requirement.multiple() ? ASSIGNEE_LIST_SUFFIX : ASSIGNEE_SUFFIX),
                    requirement.multiple() ? users : users.getFirst());
        }
        return Map.copyOf(technicalVariables);
    }

    private Map<String, ParticipantAssignment> assignmentMap(List<ParticipantAssignment> assignments) {
        Map<String, ParticipantAssignment> values = new LinkedHashMap<>();
        if (assignments == null) {
            return values;
        }
        for (ParticipantAssignment assignment : assignments) {
            String activityId = assignment.activityId().trim();
            if (values.putIfAbsent(activityId, assignment) != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "Duplicate participant assignment: " + activityId);
            }
        }
        return values;
    }

    private List<UserTask> firstUserTasks(String processDefinitionId, Map<String, Object> variables) {
        BpmnModel model = requireModel(processDefinitionId);
        List<FlowNode> startEvents = model.getMainProcess().getFlowElements().stream()
                .filter(StartEvent.class::isInstance)
                .map(StartEvent.class::cast)
                .map(FlowNode.class::cast)
                .toList();
        return nextUserTasks(startEvents, variables);
    }

    private List<UserTask> targetTasks(
            Task task, TaskParticipantAction action, String targetActivityId, Map<String, Object> variables) {
        BpmnModel model = requireModel(task.getProcessDefinitionId());
        if (action == TaskParticipantAction.REJECT) {
            UserTask target = StringUtils.hasText(targetActivityId)
                    ? findUserTask(model, targetActivityId.trim())
                    : model.getMainProcess().getFlowElements().stream()
                            .filter(UserTask.class::isInstance)
                            .map(UserTask.class::cast)
                            .findFirst()
                            .orElse(null);
            if (target == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Reject target user task does not exist");
            }
            return List.of(target);
        }
        FlowElement current = model.getMainProcess().getFlowElement(task.getTaskDefinitionKey(), true);
        if (!(current instanceof FlowNode flowNode)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Current workflow activity does not exist");
        }
        return nextUserTasks(List.of(flowNode), variables);
    }

    private List<UserTask> nextUserTasks(Collection<FlowNode> sources, Map<String, Object> variables) {
        Queue<FlowElement> queue = new ArrayDeque<>();
        for (FlowNode source : sources) {
            enqueueTargets(queue, selectedOutgoingFlows(source, variables));
        }
        Set<String> visited = new HashSet<>();
        Map<String, UserTask> results = new LinkedHashMap<>();
        while (!queue.isEmpty()) {
            FlowElement element = queue.remove();
            if (!visited.add(element.getId())) {
                continue;
            }
            if (element instanceof UserTask userTask) {
                results.putIfAbsent(userTask.getId(), userTask);
                continue;
            }
            if (element instanceof FlowNode flowNode) {
                enqueueTargets(queue, selectedOutgoingFlows(flowNode, variables));
            }
        }
        return List.copyOf(results.values());
    }

    private List<SequenceFlow> selectedOutgoingFlows(FlowNode node, Map<String, Object> variables) {
        if (node instanceof ExclusiveGateway gateway) {
            return selectExclusiveFlow(gateway, variables);
        }
        if (node instanceof InclusiveGateway gateway) {
            return selectInclusiveFlows(gateway, variables);
        }
        if (node instanceof Gateway) {
            return node.getOutgoingFlows();
        }
        return selectConditionalActivityFlows(node, variables);
    }

    private List<SequenceFlow> selectConditionalActivityFlows(
            FlowNode node, Map<String, Object> variables) {
        List<SequenceFlow> outgoingFlows = node.getOutgoingFlows();
        if (outgoingFlows.stream().noneMatch(this::hasCondition)) {
            return outgoingFlows;
        }
        SequenceFlow defaultFlow = node instanceof Activity activity
                ? defaultActivityFlow(activity) : null;
        List<SequenceFlow> selected = outgoingFlows.stream()
                .filter(flow -> flow != defaultFlow)
                .filter(this::hasCondition)
                .filter(flow -> conditionMatches(flow, variables))
                .toList();
        if (!selected.isEmpty()) {
            return selected;
        }
        if (defaultFlow != null) {
            return List.of(defaultFlow);
        }
        List<SequenceFlow> unconditional = outgoingFlows.stream()
                .filter(flow -> !hasCondition(flow))
                .toList();
        if (!unconditional.isEmpty()) {
            return unconditional;
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST,
                "No outgoing condition matched activity " + node.getId());
    }

    private SequenceFlow defaultActivityFlow(Activity activity) {
        if (!StringUtils.hasText(activity.getDefaultFlow())) {
            return null;
        }
        return activity.getOutgoingFlows().stream()
                .filter(flow -> activity.getDefaultFlow().equals(flow.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "Activity " + activity.getId() + " references an unknown default flow"));
    }

    private boolean hasCondition(SequenceFlow flow) {
        return StringUtils.hasText(flow.getConditionExpression());
    }

    private List<SequenceFlow> selectExclusiveFlow(ExclusiveGateway gateway, Map<String, Object> variables) {
        SequenceFlow defaultFlow = defaultFlow(gateway);
        for (SequenceFlow flow : gateway.getOutgoingFlows()) {
            if (flow == defaultFlow) {
                continue;
            }
            if (conditionMatches(flow, variables)) {
                return List.of(flow);
            }
        }
        if (defaultFlow != null) {
            return List.of(defaultFlow);
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST,
                "No outgoing condition matched gateway " + gateway.getId());
    }

    private List<SequenceFlow> selectInclusiveFlows(InclusiveGateway gateway, Map<String, Object> variables) {
        SequenceFlow defaultFlow = defaultFlow(gateway);
        List<SequenceFlow> selected = gateway.getOutgoingFlows().stream()
                .filter(flow -> flow != defaultFlow)
                .filter(flow -> conditionMatches(flow, variables))
                .toList();
        if (!selected.isEmpty()) {
            return selected;
        }
        if (defaultFlow != null) {
            return List.of(defaultFlow);
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST,
                "No outgoing condition matched gateway " + gateway.getId());
    }

    private SequenceFlow defaultFlow(Gateway gateway) {
        if (!StringUtils.hasText(gateway.getDefaultFlow())) {
            return null;
        }
        return gateway.getOutgoingFlows().stream()
                .filter(flow -> gateway.getDefaultFlow().equals(flow.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "Gateway " + gateway.getId() + " references an unknown default flow"));
    }

    private boolean conditionMatches(SequenceFlow flow, Map<String, Object> variables) {
        if (!StringUtils.hasText(flow.getConditionExpression())) {
            return true;
        }
        try {
            Object result = expressionManager.createExpression(flow.getConditionExpression())
                    .getValue(new MapDelegateVariableContainer(new HashMap<>(variables), null));
            if (result instanceof Boolean booleanResult) {
                return booleanResult;
            }
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Gateway condition must evaluate to a boolean: " + flow.getId());
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Gateway condition cannot be evaluated for flow " + flow.getId(), exception);
        }
    }

    private void enqueueTargets(Queue<FlowElement> queue, Collection<SequenceFlow> flows) {
        flows.stream()
                .map(SequenceFlow::getTargetFlowElement)
                .filter(java.util.Objects::nonNull)
                .forEach(queue::add);
    }

    private AssignmentType expectedType(UserTask task) {
        if (task.getLoopCharacteristics() != null) {
            return AssignmentType.COUNTERSIGN_USERS;
        }
        boolean users = !CollectionUtils.isEmpty(task.getCandidateUsers());
        boolean groups = !CollectionUtils.isEmpty(task.getCandidateGroups());
        if (users && groups) {
            return AssignmentType.MIXED;
        }
        if (users) {
            return AssignmentType.CANDIDATE_USERS;
        }
        if (groups) {
            return AssignmentType.CANDIDATE_GROUPS;
        }
        return AssignmentType.ASSIGNEE;
    }

    private boolean requiresPlatformResolution(UserTask task, AssignmentType type) {
        return switch (type) {
            case ASSIGNEE -> !StringUtils.hasText(task.getAssignee()) || usesResolver(task.getAssignee());
            case CANDIDATE_USERS, MIXED -> !hasStaticValue(task.getCandidateUsers());
            case COUNTERSIGN_USERS -> {
                MultiInstanceLoopCharacteristics loop = task.getLoopCharacteristics();
                String collection = loop == null ? null : loop.getCollectionString();
                yield !StringUtils.hasText(collection) || usesResolver(collection);
            }
            case CANDIDATE_GROUPS -> false;
        };
    }

    private boolean hasStaticValue(List<String> values) {
        return values != null && values.stream()
                .anyMatch(value -> StringUtils.hasText(value) && !usesResolver(value));
    }

    private boolean usesResolver(String value) {
        return StringUtils.hasText(value) && value.contains(RESOLVER_NAME);
    }

    private boolean hasParticipantVariable(
            Map<String, Object> variables, String activityId, AssignmentType type) {
        String suffix = type == AssignmentType.ASSIGNEE ? ASSIGNEE_SUFFIX : ASSIGNEE_LIST_SUFFIX;
        Object value = variables.get(activityId + suffix);
        return value instanceof Collection<?> collection ? !collection.isEmpty() : value != null;
    }

    private List<String> normalizeUsers(List<String> users) {
        if (users == null) {
            return List.of();
        }
        return users.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private Map<String, Object> safeVariables(Map<String, Object> variables) {
        return variables == null ? Map.of() : new HashMap<>(variables);
    }

    private BpmnModel requireModel(String processDefinitionId) {
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
        if (model == null || model.getMainProcess() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process model does not exist");
        }
        return model;
    }

    private UserTask findUserTask(BpmnModel model, String activityId) {
        FlowElement element = model.getMainProcess().getFlowElement(activityId, true);
        return element instanceof UserTask userTask ? userTask : null;
    }

    private String displayName(UserTask task) {
        return StringUtils.hasText(task.getName()) ? task.getName().trim() : task.getId();
    }
}
