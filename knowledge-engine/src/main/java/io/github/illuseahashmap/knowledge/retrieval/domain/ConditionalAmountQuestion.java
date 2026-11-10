package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.regex.Pattern;

/** Explicit requests for the trigger of a monetary decision need the row AND its local qualification. */
public final class ConditionalAmountQuestion {
    public enum Role { HEADER, ROW, QUALIFIER }
    private static final Pattern MONEY=Pattern.compile("[0-9]\\s*万?元");
    private static final Pattern TRIGGER=Pattern.compile("条件|何种情况|什么情况|何时(?:需要|必须)");
    private static final Pattern REVIEW=Pattern.compile("审批|核签|复核|会审|例会|会签|上会|委员会|复审");
    public static boolean matches(String query) {
        return MONEY.matcher(query).find() && REVIEW.matcher(query).find() && TRIGGER.matcher(query).find()
                && PolicyComparison.parse(query).isEmpty();
    }
    private ConditionalAmountQuestion() {}
}
