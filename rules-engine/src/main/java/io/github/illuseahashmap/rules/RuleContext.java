package io.github.illuseahashmap.rules;

import java.util.Map;
import java.util.Collections;
import java.util.HashMap;

public record RuleContext(Map<String, Object> variables) {

    public RuleContext {
        variables = variables == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(variables));
    }

    public static RuleContext empty() {
        return new RuleContext(Map.of());
    }

    public static RuleContext of(Map<String, Object> variables) {
        return new RuleContext(variables);
    }

    public Object get(String variableName) {
        return variables.get(variableName);
    }
}
