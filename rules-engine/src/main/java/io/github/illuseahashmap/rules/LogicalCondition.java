package io.github.illuseahashmap.rules;

import java.util.List;
import java.util.Objects;

public record LogicalCondition(RuleLogicOperator operator, List<ConditionNode> children) implements ConditionNode {

    public LogicalCondition {
        operator = operator == null ? RuleLogicOperator.AND : operator;
        children = children == null ? List.of() : List.copyOf(children);
    }

    @Override
    public boolean matches(RuleContext context) {
        return switch (operator) {
            case AND -> children.stream().allMatch(child -> child != null && child.matches(context));
            case OR -> children.stream().anyMatch(child -> child != null && child.matches(context));
            case NOT -> children.stream()
                    .filter(Objects::nonNull)
                    .findFirst()
                    .map(child -> !child.matches(context))
                    .orElse(true);
        };
    }
}
