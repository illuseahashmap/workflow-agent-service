package io.github.illuseahashmap.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultRuleEngineTest {

    private final RuleEngine ruleEngine = new DefaultRuleEngine();

    @Test
    void returnsFirstMatchingRuleByPriority() {
        RuleDefinition fallback = new RuleDefinition("fallback", 100, null, Map.of("assignee", "fallback"));
        RuleDefinition preferred = new RuleDefinition(
                "preferred",
                10,
                new LogicalCondition(RuleLogicOperator.AND, List.of(
                        new VariableCondition("amount", RuleConditionOperator.GE, "100"),
                        new VariableCondition("region", RuleConditionOperator.IN, List.of("CN", "SG")))),
                Map.of("assignee", "reviewer"));

        RuleEvaluationResult result = ruleEngine.evaluate(
                List.of(fallback, preferred),
                RuleContext.of(Map.of("amount", 120, "region", "CN")));

        assertTrue(result.matched());
        assertEquals("preferred", result.ruleCode());
        assertEquals("reviewer", result.result().get("assignee"));
    }

    @Test
    void returnsUnmatchedForEmptyRules() {
        assertFalse(ruleEngine.evaluate(List.of(), RuleContext.empty()).matched());
    }
}
