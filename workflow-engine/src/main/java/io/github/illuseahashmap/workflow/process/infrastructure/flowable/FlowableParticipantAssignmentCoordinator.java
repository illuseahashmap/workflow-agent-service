package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;
import io.github.illuseahashmap.workflow.process.application.dto.ParticipantAssignment;
import io.github.illuseahashmap.workflow.process.application.dto.ParticipantRequirementView;
import io.github.illuseahashmap.workflow.process.application.dto.TaskParticipantAction;
import io.github.illuseahashmap.workflow.process.application.port.ParticipantDirectory;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Component
public class FlowableParticipantAssignmentCoordinator {

    private static final String ASSIGNEE_SUFFIX = "_assignee";
    private static final String ASSIGNEE_LIST_SUFFIX = "_assigneeList";
    private static final String RESOLVER_NAME = "assigneeService";

    private final AssignmentRuleService assignmentRuleService;
    private final ParticipantDirectory participantDirectory;
    private final FlowableUserTaskPathResolver pathResolver;

    public FlowableParticipantAssignmentCoordinator(RepositoryService repositoryService,
                                                    AssignmentRuleService assignmentRuleService,
                                                    ParticipantDirectory participantDirectory,
                                                    org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl processEngineConfiguration) {
        this.assignmentRuleService = assignmentRuleService;
        this.participantDirectory = participantDirectory;
        this.pathResolver = new FlowableUserTaskPathResolver(repositoryService, processEngineConfiguration);
    }

    public List<ParticipantRequirementView> requirementsForStart(
            String tenantId, String processDefinitionId, Map<String, Object> variables) {
        return requirements(tenantId, processDefinitionId,
                pathResolver.firstUserTasks(processDefinitionId, safeVariables(variables)),
                safeVariables(variables), true);
    }

    public List<ParticipantRequirementView> requirementsForTask(
            String tenantId, Task task, TaskParticipantAction action,
            String targetActivityId, Map<String, Object> variables) {
        return requirements(tenantId, task.getProcessDefinitionId(),
                pathResolver.targetTasks(task, action, targetActivityId, safeVariables(variables)),
                safeVariables(variables), false);
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
                    task.getId(), pathResolver.displayName(task), type,
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

}
