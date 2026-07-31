package io.github.illuseahashmap.rules;

public interface RuleEngine<R> {

    RuleEvaluationResult evaluate(R rule, RuleContext context);
}
