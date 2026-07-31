package io.github.illuseahashmap.rules;

import java.util.Map;
import java.util.Collections;
import java.util.HashMap;

public record RuleEvaluationResult(boolean matched, String ruleCode, Map<String, Object> result) {

    public RuleEvaluationResult {
        result = result == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(result));
    }

    public static RuleEvaluationResult matched(String ruleCode, Map<String, Object> result) {
        return new RuleEvaluationResult(true, ruleCode, result);
    }

    public static RuleEvaluationResult unmatched() {
        return new RuleEvaluationResult(false, null, Map.of());
    }
}
