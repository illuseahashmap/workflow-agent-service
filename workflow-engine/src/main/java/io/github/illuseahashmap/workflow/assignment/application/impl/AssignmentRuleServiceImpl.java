package io.github.illuseahashmap.workflow.assignment.application.impl;

import io.github.illuseahashmap.rules.ConditionNode;
import io.github.illuseahashmap.rules.LogicalCondition;
import io.github.illuseahashmap.rules.RuleContext;
import io.github.illuseahashmap.rules.RuleDefinition;
import io.github.illuseahashmap.rules.RuleEngine;
import io.github.illuseahashmap.rules.RuleEvaluationResult;
import io.github.illuseahashmap.rules.RuleLogicOperator;
import io.github.illuseahashmap.rules.VariableCondition;
import io.github.illuseahashmap.workflow.assignment.application.AssignmentRuleService;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentConditionCommand;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleCommand;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleInheritResult;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentConditionView;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentRuleView;
import io.github.illuseahashmap.workflow.assignment.application.dto.AssignmentTargetView;
import io.github.illuseahashmap.workflow.assignment.application.port.ProcessDefinitionCatalog;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentCondition;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentTarget;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentTargetType;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;
import io.github.illuseahashmap.workflow.assignment.domain.EmptyUserStrategy;
import io.github.illuseahashmap.workflow.assignment.domain.NodeAssignmentRule;
import io.github.illuseahashmap.workflow.assignment.domain.NodeAssignmentRuleRepository;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import io.github.illuseahashmap.workflow.shared.response.PageResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AssignmentRuleServiceImpl implements AssignmentRuleService {

    private final NodeAssignmentRuleRepository ruleRepository;
    private final ProcessDefinitionCatalog definitionCatalog;
    private final RuleEngine ruleEngine;
    private final TenantProvider tenantProvider;

    public AssignmentRuleServiceImpl(NodeAssignmentRuleRepository ruleRepository,
                                     ProcessDefinitionCatalog definitionCatalog,
                                     RuleEngine ruleEngine,
                                     TenantProvider tenantProvider) {
        this.ruleRepository = ruleRepository;
        this.definitionCatalog = definitionCatalog;
        this.ruleEngine = ruleEngine;
        this.tenantProvider = tenantProvider;
    }

    @Override
    public PageResult<AssignmentRuleView> page(Integer pageNum, Integer pageSize,
                                               String processDefinitionKey, String processDefinitionId,
                                               Integer version, String taskDefinitionKey, String variableName,
                                               String assignmentType, String emptyUserStrategy) {
        int normalizedPageNum = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int normalizedPageSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 100);
        PageSlice<NodeAssignmentRule> page = ruleRepository.page(new NodeAssignmentRuleRepository.RulePageCriteria(
                normalizedPageNum, normalizedPageSize, tenantProvider.current().tenantId(),
                processDefinitionKey, processDefinitionId, version, taskDefinitionKey,
                variableName, parseEnum(assignmentType, AssignmentType.class),
                parseEnum(emptyUserStrategy, EmptyUserStrategy.class)));
        return new PageResult<>(page.total(), page.pageNumber(), page.pageSize(),
                page.items().stream().map(this::toView).toList());
    }

    @Override
    public NodeAssignmentRule match(String tenantId, String processDefinitionId,
                                    String taskDefinitionKey, Map<String, Object> variables) {
        List<NodeAssignmentRule> rules = ruleRepository.findEnabled(tenantId, processDefinitionId, taskDefinitionKey);
        List<RuleDefinition> definitions = rules.stream().map(this::toRuleDefinition).toList();
        RuleEvaluationResult result = ruleEngine.evaluate(definitions, RuleContext.of(variables));
        if (!result.matched()) {
            return null;
        }
        long ruleId = Long.parseLong(result.ruleCode());
        return rules.stream().filter(rule -> rule.id() == ruleId).findFirst().orElse(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssignmentRuleView create(AssignmentRuleCommand command) {
        String tenantId = tenantProvider.current().tenantId();
        return toView(ruleRepository.save(toRule(null, tenantId, command)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(long id, AssignmentRuleCommand command) {
        String tenantId = tenantProvider.current().tenantId();
        NodeAssignmentRule existingRule = ruleRepository.findById(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Assignment rule does not exist"));
        validateBindingUnchanged(existingRule, command);
        ruleRepository.update(toRule(id, tenantId, command));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(long id) {
        String tenantId = tenantProvider.current().tenantId();
        ruleRepository.findById(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Assignment rule does not exist"));
        ruleRepository.delete(tenantId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByProcessDefinition(String processDefinitionId) {
        String tenantId = tenantProvider.current().tenantId();
        ruleRepository.deleteByProcessDefinition(tenantId, processDefinitionId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByProcessDefinitionKey(String processDefinitionKey) {
        String tenantId = tenantProvider.current().tenantId();
        ruleRepository.deleteByProcessDefinitionKey(tenantId, processDefinitionKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssignmentRuleInheritResult inherit(String processDefinitionId) {
        String tenantId = tenantProvider.current().tenantId();
        ProcessDefinitionCatalog.DefinitionInfo target = requireDefinition(tenantId, processDefinitionId);
        ProcessDefinitionCatalog.DefinitionInfo source = definitionCatalog.findVersions(tenantId, target.key()).stream()
                .filter(candidate -> candidate.version() < target.version())
                .filter(candidate -> ruleRepository.count(tenantId, candidate.id()) > 0)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "No historical assignment rule version exists"));
        int copied = 0;
        List<String> skippedReasons = new ArrayList<>();
        Set<String> configuredTaskKeys = ruleRepository.findByProcessDefinition(tenantId, target.id()).stream()
                .map(NodeAssignmentRule::taskDefinitionKey)
                .collect(Collectors.toCollection(HashSet::new));
        for (NodeAssignmentRule sourceRule : ruleRepository.findByProcessDefinition(tenantId, source.id())) {
            if (configuredTaskKeys.contains(sourceRule.taskDefinitionKey())) {
                skippedReasons.add(sourceRule.taskDefinitionKey() + ": target task already has assignment rules");
                continue;
            }
            try {
                validateAssignmentType(target.id(), sourceRule.taskDefinitionKey(), sourceRule.assignmentType());
                ruleRepository.save(copyForDefinition(sourceRule, target));
                copied++;
            } catch (BusinessException exception) {
                skippedReasons.add(sourceRule.taskDefinitionKey() + ": " + exception.getMessage());
            }
        }
        return new AssignmentRuleInheritResult(
                source.id(), source.version(), target.id(), target.version(),
                copied, skippedReasons.size(), List.copyOf(skippedReasons));
    }

    private NodeAssignmentRule toRule(Long id, String tenantId, AssignmentRuleCommand command) {
        ProcessDefinitionCatalog.DefinitionInfo definition = requireDefinition(tenantId, command.processDefinitionId());
        validateAssignmentType(definition.id(), command.taskDefinitionKey(), command.assignmentType());
        List<AssignmentTarget> targets = buildTargets(command);
        validateTargets(command, targets);
        List<AssignmentCondition> conditions = buildConditions(command.conditions());
        return new NodeAssignmentRule(
                id, tenantId, definition.id(), definition.key(), definition.version(),
                command.taskDefinitionKey().trim(), command.priority() == null ? 100 : command.priority(),
                command.assignmentType(), command.emptyUserStrategy(),
                command.enabled() == null || command.enabled(), normalize(command.description()),
                conditions, targets, null, null);
    }

    private void validateBindingUnchanged(NodeAssignmentRule existingRule, AssignmentRuleCommand command) {
        if (!existingRule.processDefinitionId().equals(command.processDefinitionId().trim())
                || !existingRule.taskDefinitionKey().equals(command.taskDefinitionKey().trim())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Process definition version and task binding cannot be changed; create a new rule instead");
        }
    }

    private ProcessDefinitionCatalog.DefinitionInfo requireDefinition(String tenantId, String processDefinitionId) {
        return definitionCatalog.findById(tenantId, processDefinitionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Process definition does not exist"));
    }

    private void validateAssignmentType(String processDefinitionId, String taskDefinitionKey, AssignmentType actual) {
        try {
            AssignmentType expected = definitionCatalog.expectedAssignmentType(processDefinitionId, taskDefinitionKey);
            if (expected != actual) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "Task " + taskDefinitionKey + " requires assignment type " + expected);
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, exception.getMessage());
        }
    }

    private List<AssignmentTarget> buildTargets(AssignmentRuleCommand command) {
        List<AssignmentTarget> targets = new ArrayList<>();
        addTargets(targets, AssignmentTargetType.ASSIGNEE, command.assignees());
        addTargets(targets, AssignmentTargetType.CANDIDATE_USER, command.candidateUsers());
        addTargets(targets, AssignmentTargetType.CANDIDATE_GROUP, command.candidateGroups());
        addTargets(targets, AssignmentTargetType.COUNTERSIGN_USER, command.countersignUsers());
        addTargets(targets, AssignmentTargetType.FALLBACK_ASSIGNEE,
                StringUtils.hasText(command.fallbackAssignee()) ? List.of(command.fallbackAssignee()) : List.of());
        return targets;
    }

    private void addTargets(List<AssignmentTarget> targets, AssignmentTargetType type, List<String> values) {
        for (String value : normalizeList(values)) {
            targets.add(new AssignmentTarget(null, type, value, (targets.size() + 1) * 10));
        }
    }

    private void validateTargets(AssignmentRuleCommand command, List<AssignmentTarget> targets) {
        Map<AssignmentTargetType, Long> counts = new HashMap<>();
        targets.forEach(target -> counts.merge(target.targetType(), 1L, Long::sum));
        boolean valid = switch (command.assignmentType()) {
            case ASSIGNEE -> counts.getOrDefault(AssignmentTargetType.ASSIGNEE, 0L) == 1;
            case CANDIDATE_USERS -> counts.getOrDefault(AssignmentTargetType.CANDIDATE_USER, 0L) > 0;
            case CANDIDATE_GROUPS -> counts.getOrDefault(AssignmentTargetType.CANDIDATE_GROUP, 0L) > 0;
            case COUNTERSIGN_USERS -> counts.getOrDefault(AssignmentTargetType.COUNTERSIGN_USER, 0L) > 0;
            case MIXED -> counts.getOrDefault(AssignmentTargetType.CANDIDATE_USER, 0L) > 0
                    || counts.getOrDefault(AssignmentTargetType.CANDIDATE_GROUP, 0L) > 0;
        };
        if (!valid) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Assignment targets do not match assignment type " + command.assignmentType());
        }
        if (command.emptyUserStrategy() == EmptyUserStrategy.TO_ASSIGNEE
                && counts.getOrDefault(AssignmentTargetType.FALLBACK_ASSIGNEE, 0L) != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "TO_ASSIGNEE requires one fallback assignee");
        }
    }

    private List<AssignmentCondition> buildConditions(List<AssignmentConditionCommand> commands) {
        if (commands == null) {
            return List.of();
        }
        List<AssignmentCondition> conditions = new ArrayList<>();
        for (int index = 0; index < commands.size(); index++) {
            AssignmentConditionCommand command = commands.get(index);
            conditions.add(new AssignmentCondition(
                    null,
                    command.variableName().trim(),
                    command.operator() == null ? io.github.illuseahashmap.rules.RuleConditionOperator.EQ : command.operator(),
                    normalize(command.variableValue()),
                    command.sortOrder() == null ? (index + 1) * 10 : command.sortOrder()));
        }
        return conditions;
    }

    private RuleDefinition toRuleDefinition(NodeAssignmentRule rule) {
        List<ConditionNode> conditions = rule.conditions().stream()
                .map(condition -> new VariableCondition(
                        condition.variableName(), condition.operator(), expectedValue(condition)))
                .map(ConditionNode.class::cast)
                .toList();
        return new RuleDefinition(
                String.valueOf(rule.id()),
                rule.priority(),
                new LogicalCondition(RuleLogicOperator.AND, conditions),
                Map.of("ruleId", rule.id()));
    }

    private Object expectedValue(AssignmentCondition condition) {
        return switch (condition.operator()) {
            case IN, NOT_IN -> normalizeList(condition.variableValue() == null
                    ? List.of()
                    : List.of(condition.variableValue().split(",")));
            default -> condition.variableValue();
        };
    }

    private NodeAssignmentRule copyForDefinition(NodeAssignmentRule source,
                                                 ProcessDefinitionCatalog.DefinitionInfo target) {
        List<AssignmentCondition> conditions = source.conditions().stream()
                .map(condition -> new AssignmentCondition(
                        null, condition.variableName(), condition.operator(), condition.variableValue(), condition.sortOrder()))
                .toList();
        List<AssignmentTarget> targets = source.targets().stream()
                .map(value -> new AssignmentTarget(null, value.targetType(), value.targetValue(), value.sortOrder()))
                .toList();
        return new NodeAssignmentRule(
                null, source.tenantId(), target.id(), target.key(), target.version(), source.taskDefinitionKey(),
                source.priority(), source.assignmentType(), source.emptyUserStrategy(), source.enabled(),
                source.description(), conditions, targets, null, null);
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private <E extends Enum<E>> E parseEnum(String value, Class<E> enumType) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Unsupported " + enumType.getSimpleName() + ": " + value);
        }
    }

    private AssignmentRuleView toView(NodeAssignmentRule rule) {
        List<AssignmentConditionView> conditions = rule.conditions().stream()
                .map(condition -> new AssignmentConditionView(
                        condition.id(), condition.variableName(), condition.operator(),
                        condition.variableValue(), condition.sortOrder()))
                .toList();
        List<AssignmentTargetView> targets = rule.targets().stream()
                .map(target -> new AssignmentTargetView(
                        target.id(), target.targetType(), target.targetValue(), target.sortOrder()))
                .toList();
        return new AssignmentRuleView(
                rule.id(), rule.tenantId(), rule.processDefinitionId(), rule.processDefinitionKey(), rule.version(),
                rule.taskDefinitionKey(), rule.priority(), rule.assignmentType(), rule.emptyUserStrategy(),
                rule.enabled(), rule.description(), conditions, targets, rule.createdAt(), rule.updatedAt());
    }
}
