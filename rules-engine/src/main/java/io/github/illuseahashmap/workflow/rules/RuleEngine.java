package io.github.illuseahashmap.workflow.rules;

public interface RuleEngine<R> {

    RuleEvaluationResult evaluate(R rule, RuleContext context);
}
