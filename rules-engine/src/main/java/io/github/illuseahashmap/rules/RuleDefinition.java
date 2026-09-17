package io.github.illuseahashmap.rules;

import java.util.Map;
import java.util.Collections;
import java.util.HashMap;

public record RuleDefinition(String ruleCode, int priority, ConditionNode condition, Map<String, Object> result) {

    public RuleDefinition {
        result = result == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(result));
    }

    public boolean matches(RuleContext context) {
        return condition == null || condition.matches(context);
    }
}
