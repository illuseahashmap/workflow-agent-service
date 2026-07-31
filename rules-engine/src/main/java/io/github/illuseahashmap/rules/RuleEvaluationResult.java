package io.github.illuseahashmap.rules;

public record RuleEvaluationResult(boolean matched, String reason) {

    public static RuleEvaluationResult match() {
        return new RuleEvaluationResult(true, "matched");
    }

    public static RuleEvaluationResult notMatched(String reason) {
        return new RuleEvaluationResult(false, reason);
    }
}
