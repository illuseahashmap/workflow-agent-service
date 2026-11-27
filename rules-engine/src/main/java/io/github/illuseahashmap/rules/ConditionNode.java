package io.github.illuseahashmap.rules;

@FunctionalInterface
public interface ConditionNode {

    boolean matches(RuleContext context);
}
