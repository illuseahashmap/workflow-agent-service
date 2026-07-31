package io.github.illuseahashmap.workflow.rules;

import java.util.Map;

public record RuleContext(Map<String, Object> variables) {

    public Object getVariable(String variableName) {
        return variables == null ? null : variables.get(variableName);
    }
}
