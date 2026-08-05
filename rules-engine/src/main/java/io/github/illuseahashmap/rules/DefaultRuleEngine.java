package io.github.illuseahashmap.rules;

import java.util.Collection;
import java.util.Comparator;

public final class DefaultRuleEngine implements RuleEngine {

    @Override
    public RuleEvaluationResult evaluate(Collection<RuleDefinition> rules, RuleContext context) {
        if (rules == null || rules.isEmpty()) {
            return RuleEvaluationResult.unmatched();
        }
        RuleContext safeContext = context == null ? RuleContext.empty() : context;
        return rules.stream()
                .filter(rule -> rule != null && rule.matches(safeContext))
                .min(Comparator.comparingInt(RuleDefinition::priority)
                        .thenComparing(RuleDefinition::ruleCode, Comparator.nullsLast(String::compareTo)))
                .map(rule -> RuleEvaluationResult.matched(rule.ruleCode(), rule.result()))
                .orElseGet(RuleEvaluationResult::unmatched);
    }
}
