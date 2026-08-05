package io.github.illuseahashmap.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

class VariableConditionTest {

    @Test
    void invalidNumericValuesNeverMatchOrderingOperators() {
        RuleContext context = RuleContext.of(Map.of("amount", "not-a-number"));

        assertFalse(new VariableCondition("amount", RuleConditionOperator.GT, 1).matches(context));
        assertFalse(new VariableCondition("amount", RuleConditionOperator.GE, 1).matches(context));
        assertFalse(new VariableCondition("amount", RuleConditionOperator.LT, 1).matches(context));
        assertFalse(new VariableCondition("amount", RuleConditionOperator.LE, 1).matches(context));
    }

    @Test
    void supportsExistenceAndEqualityChecks() {
        RuleContext context = RuleContext.of(Map.of("status", "READY"));

        assertTrue(new VariableCondition("status", RuleConditionOperator.EXISTS, null).matches(context));
        assertTrue(new VariableCondition("status", RuleConditionOperator.EQ, " READY ").matches(context));
        assertTrue(new VariableCondition("missing", RuleConditionOperator.NOT_EXISTS, null).matches(context));
    }

    @Test
    void acceptsNullWorkflowVariableValues() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("optional", null);

        RuleContext context = RuleContext.of(variables);

        assertTrue(new VariableCondition("optional", RuleConditionOperator.NOT_EXISTS, null).matches(context));
    }
}
