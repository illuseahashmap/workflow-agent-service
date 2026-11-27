package io.github.illuseahashmap.rules;

import java.util.Collection;

public interface RuleEngine {

    RuleEvaluationResult evaluate(Collection<RuleDefinition> rules, RuleContext context);
}
