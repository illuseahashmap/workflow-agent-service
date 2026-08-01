package io.github.illuseahashmap.workflow.assignment.infrastructure.persistence;

import io.github.illuseahashmap.rules.RuleConditionOperator;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentCondition;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentTarget;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentTargetType;
import io.github.illuseahashmap.workflow.assignment.domain.AssignmentType;
import io.github.illuseahashmap.workflow.assignment.domain.EmptyUserStrategy;
import io.github.illuseahashmap.workflow.assignment.domain.NodeAssignmentRule;
import io.github.illuseahashmap.workflow.assignment.domain.NodeAssignmentRuleRepository;
import io.github.illuseahashmap.workflow.shared.model.PageSlice;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class JdbcNodeAssignmentRuleRepository implements NodeAssignmentRuleRepository {

    private static final String SELECT_RULE = """
            SELECT id, tenant_id, process_definition_id, process_definition_key, version,
                   task_definition_key, priority, assignment_type, empty_user_strategy,
                   enabled, description, created_at, updated_at
            FROM workflow_node_assignment_rule
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcNodeAssignmentRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public PageSlice<NodeAssignmentRule> page(RulePageCriteria criteria) {
        SqlCriteria sqlCriteria = buildCriteria(criteria);
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_node_assignment_rule r" + sqlCriteria.where(),
                Long.class,
                sqlCriteria.arguments().toArray());
        List<Object> pageArguments = new ArrayList<>(sqlCriteria.arguments());
        pageArguments.add(criteria.pageSize());
        pageArguments.add((criteria.pageNum() - 1) * criteria.pageSize());
        List<NodeAssignmentRule> rules = jdbcTemplate.query(
                SELECT_RULE.replace("FROM workflow_node_assignment_rule", "FROM workflow_node_assignment_rule r")
                        + sqlCriteria.where() + " ORDER BY priority ASC, id ASC LIMIT ? OFFSET ?",
                this::mapRule,
                pageArguments.toArray());
        return new PageSlice<>(total == null ? 0 : total, criteria.pageNum(), criteria.pageSize(), hydrate(rules));
    }

    @Override
    public List<NodeAssignmentRule> findEnabled(String tenantId, String processDefinitionId,
                                                String taskDefinitionKey) {
        List<NodeAssignmentRule> rules = jdbcTemplate.query(SELECT_RULE + """
                WHERE tenant_id = ? AND process_definition_id = ? AND task_definition_key = ? AND enabled = 1
                ORDER BY priority ASC, id ASC
                """, this::mapRule, tenantId, processDefinitionId, taskDefinitionKey);
        return hydrate(rules);
    }

    @Override
    public List<NodeAssignmentRule> findByProcessDefinition(String tenantId, String processDefinitionId) {
        return hydrate(jdbcTemplate.query(SELECT_RULE + """
                WHERE tenant_id = ? AND process_definition_id = ? ORDER BY priority ASC, id ASC
                """, this::mapRule, tenantId, processDefinitionId));
    }

    @Override
    public Optional<NodeAssignmentRule> findById(String tenantId, long id) {
        return hydrate(jdbcTemplate.query(
                SELECT_RULE + " WHERE tenant_id = ? AND id = ?", this::mapRule, tenantId, id))
                .stream().findFirst();
    }

    @Override
    public NodeAssignmentRule save(NodeAssignmentRule rule) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO workflow_node_assignment_rule
                        (tenant_id, process_definition_id, process_definition_key, version,
                         task_definition_key, priority, assignment_type, empty_user_strategy,
                         enabled, description, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, Statement.RETURN_GENERATED_KEYS);
            bindRule(statement, rule, false);
            return statement;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        saveChildren(id, rule);
        return findById(rule.tenantId(), id).orElseThrow();
    }

    @Override
    public void update(NodeAssignmentRule rule) {
        jdbcTemplate.update("""
                UPDATE workflow_node_assignment_rule
                SET process_definition_id = ?, process_definition_key = ?, version = ?,
                    task_definition_key = ?, priority = ?, assignment_type = ?, empty_user_strategy = ?,
                    enabled = ?, description = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND id = ?
                """, rule.processDefinitionId(), rule.processDefinitionKey(), rule.version(),
                rule.taskDefinitionKey(), rule.priority(), rule.assignmentType().name(),
                rule.emptyUserStrategy() == null ? null : rule.emptyUserStrategy().name(),
                rule.enabled() ? 1 : 0, rule.description(), rule.tenantId(), rule.id());
        jdbcTemplate.update("DELETE FROM workflow_assignment_target WHERE rule_id = ?", rule.id());
        jdbcTemplate.update("DELETE FROM workflow_node_assignment_rule_condition WHERE rule_id = ?", rule.id());
        saveChildren(rule.id(), rule);
    }

    @Override
    public void delete(String tenantId, long id) {
        jdbcTemplate.update("DELETE FROM workflow_node_assignment_rule WHERE tenant_id = ? AND id = ?", tenantId, id);
    }

    @Override
    public void deleteByProcessDefinition(String tenantId, String processDefinitionId) {
        jdbcTemplate.update("""
                DELETE FROM workflow_node_assignment_rule
                WHERE tenant_id = ? AND process_definition_id = ?
                """, tenantId, processDefinitionId);
    }

    @Override
    public void deleteByProcessDefinitionKey(String tenantId, String processDefinitionKey) {
        jdbcTemplate.update("""
                DELETE FROM workflow_node_assignment_rule
                WHERE tenant_id = ? AND process_definition_key = ?
                """, tenantId, processDefinitionKey);
    }

    @Override
    public long count(String tenantId, String processDefinitionId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM workflow_node_assignment_rule
                WHERE tenant_id = ? AND process_definition_id = ?
                """, Long.class, tenantId, processDefinitionId);
        return count == null ? 0 : count;
    }

    private SqlCriteria buildCriteria(RulePageCriteria criteria) {
        StringBuilder where = new StringBuilder(" WHERE r.tenant_id = ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(criteria.tenantId());
        appendLike(where, arguments, "r.process_definition_key", criteria.processDefinitionKey());
        appendEquals(where, arguments, "r.process_definition_id", criteria.processDefinitionId());
        if (criteria.version() != null) {
            where.append(" AND r.version = ?");
            arguments.add(criteria.version());
        }
        appendLike(where, arguments, "r.task_definition_key", criteria.taskDefinitionKey());
        if (StringUtils.hasText(criteria.variableName())) {
            where.append("""
                    AND EXISTS (SELECT 1 FROM workflow_node_assignment_rule_condition c
                                WHERE c.rule_id = r.id AND c.variable_name ILIKE ?)
                    """);
            arguments.add("%" + criteria.variableName().trim() + "%");
        }
        if (criteria.assignmentType() != null) {
            where.append(" AND r.assignment_type = ?");
            arguments.add(criteria.assignmentType().name());
        }
        if (criteria.emptyUserStrategy() != null) {
            where.append(" AND r.empty_user_strategy = ?");
            arguments.add(criteria.emptyUserStrategy().name());
        }
        return new SqlCriteria(where.toString(), arguments);
    }

    private void appendLike(StringBuilder where, List<Object> arguments, String column, String value) {
        if (StringUtils.hasText(value)) {
            where.append(" AND ").append(column).append(" ILIKE ?");
            arguments.add("%" + value.trim() + "%");
        }
    }

    private void appendEquals(StringBuilder where, List<Object> arguments, String column, String value) {
        if (StringUtils.hasText(value)) {
            where.append(" AND ").append(column).append(" = ?");
            arguments.add(value.trim());
        }
    }

    private void bindRule(PreparedStatement statement, NodeAssignmentRule rule, boolean includeId)
            throws java.sql.SQLException {
        statement.setString(1, rule.tenantId());
        statement.setString(2, rule.processDefinitionId());
        statement.setString(3, rule.processDefinitionKey());
        statement.setInt(4, rule.version());
        statement.setString(5, rule.taskDefinitionKey());
        statement.setInt(6, rule.priority());
        statement.setString(7, rule.assignmentType().name());
        statement.setString(8, rule.emptyUserStrategy() == null ? null : rule.emptyUserStrategy().name());
        statement.setInt(9, rule.enabled() ? 1 : 0);
        statement.setString(10, rule.description());
        if (includeId) {
            statement.setLong(11, rule.id());
        }
    }

    private void saveChildren(long ruleId, NodeAssignmentRule rule) {
        for (AssignmentTarget target : rule.targets()) {
            jdbcTemplate.update("""
                    INSERT INTO workflow_assignment_target
                        (tenant_id, rule_id, target_type, target_value, sort_order)
                    VALUES (?, ?, ?, ?, ?)
                    """, rule.tenantId(), ruleId, target.targetType().name(), target.targetValue(), target.sortOrder());
        }
        for (AssignmentCondition condition : rule.conditions()) {
            jdbcTemplate.update("""
                    INSERT INTO workflow_node_assignment_rule_condition
                        (tenant_id, rule_id, variable_name, operator, variable_value, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, rule.tenantId(), ruleId, condition.variableName(), condition.operator().name(),
                    condition.variableValue(), condition.sortOrder());
        }
    }

    private List<NodeAssignmentRule> hydrate(List<NodeAssignmentRule> rules) {
        if (rules.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(rules.size(), "?"));
        Object[] ids = rules.stream().map(NodeAssignmentRule::id).toArray();
        Map<Long, List<AssignmentTarget>> targets = new HashMap<>();
        jdbcTemplate.query("""
                SELECT id, rule_id, target_type, target_value, sort_order
                FROM workflow_assignment_target WHERE rule_id IN (%s)
                ORDER BY rule_id, sort_order, id
                """.formatted(placeholders), resultSet -> {
            targets.computeIfAbsent(resultSet.getLong("rule_id"), ignored -> new ArrayList<>()).add(
                    new AssignmentTarget(
                            resultSet.getLong("id"),
                            AssignmentTargetType.valueOf(resultSet.getString("target_type")),
                            resultSet.getString("target_value"),
                            resultSet.getInt("sort_order")));
        }, ids);
        Map<Long, List<AssignmentCondition>> conditions = new HashMap<>();
        jdbcTemplate.query("""
                SELECT id, rule_id, variable_name, operator, variable_value, sort_order
                FROM workflow_node_assignment_rule_condition WHERE rule_id IN (%s)
                ORDER BY rule_id, sort_order, id
                """.formatted(placeholders), resultSet -> {
            conditions.computeIfAbsent(resultSet.getLong("rule_id"), ignored -> new ArrayList<>()).add(
                    new AssignmentCondition(
                            resultSet.getLong("id"),
                            resultSet.getString("variable_name"),
                            RuleConditionOperator.valueOf(resultSet.getString("operator")),
                            resultSet.getString("variable_value"),
                            resultSet.getInt("sort_order")));
        }, ids);
        return rules.stream().map(rule -> new NodeAssignmentRule(
                rule.id(), rule.tenantId(), rule.processDefinitionId(), rule.processDefinitionKey(), rule.version(),
                rule.taskDefinitionKey(), rule.priority(), rule.assignmentType(), rule.emptyUserStrategy(),
                rule.enabled(), rule.description(), conditions.getOrDefault(rule.id(), List.of()),
                targets.getOrDefault(rule.id(), List.of()), rule.createdAt(), rule.updatedAt())).toList();
    }

    private NodeAssignmentRule mapRule(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        String emptyStrategy = resultSet.getString("empty_user_strategy");
        return new NodeAssignmentRule(
                resultSet.getLong("id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("process_definition_id"),
                resultSet.getString("process_definition_key"),
                resultSet.getInt("version"),
                resultSet.getString("task_definition_key"),
                resultSet.getInt("priority"),
                AssignmentType.valueOf(resultSet.getString("assignment_type")),
                StringUtils.hasText(emptyStrategy) ? EmptyUserStrategy.valueOf(emptyStrategy) : null,
                resultSet.getInt("enabled") == 1,
                resultSet.getString("description"),
                List.of(),
                List.of(),
                resultSet.getObject("created_at", java.time.OffsetDateTime.class),
                resultSet.getObject("updated_at", java.time.OffsetDateTime.class));
    }

    private record SqlCriteria(String where, List<Object> arguments) {
    }
}
