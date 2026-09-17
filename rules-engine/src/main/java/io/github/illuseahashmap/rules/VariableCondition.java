package io.github.illuseahashmap.rules;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Objects;

public record VariableCondition(
        String variableName,
        RuleConditionOperator operator,
        Object expectedValue
) implements ConditionNode {

    public VariableCondition {
        operator = operator == null ? RuleConditionOperator.EQ : operator;
    }

    @Override
    public boolean matches(RuleContext context) {
        Object actualValue = context == null ? null : context.get(variableName);
        return switch (operator) {
            case EQ -> Objects.equals(normalize(actualValue), normalize(expectedValue));
            case NE -> !Objects.equals(normalize(actualValue), normalize(expectedValue));
            case EXISTS -> hasValue(actualValue);
            case NOT_EXISTS -> !hasValue(actualValue);
            case IN -> contains(expectedValue, actualValue);
            case NOT_IN -> !contains(expectedValue, actualValue);
            case GT -> compare(actualValue, expectedValue, value -> value > 0);
            case GE -> compare(actualValue, expectedValue, value -> value >= 0);
            case LT -> compare(actualValue, expectedValue, value -> value < 0);
            case LE -> compare(actualValue, expectedValue, value -> value <= 0);
        };
    }

    private boolean contains(Object expected, Object actual) {
        if (expected instanceof Collection<?> collection) {
            return collection.stream().anyMatch(item -> Objects.equals(normalize(item), normalize(actual)));
        }
        return Objects.equals(normalize(expected), normalize(actual));
    }

    private boolean compare(Object actual, Object expected, java.util.function.IntPredicate predicate) {
        BigDecimal actualNumber = toNumber(actual);
        BigDecimal expectedNumber = toNumber(expected);
        if (actualNumber == null || expectedNumber == null) {
            return false;
        }
        return predicate.test(actualNumber.compareTo(expectedNumber));
    }

    private BigDecimal toNumber(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean hasValue(Object value) {
        return value != null && !value.toString().isBlank();
    }

    private String normalize(Object value) {
        return value == null ? null : value.toString().trim();
    }
}
