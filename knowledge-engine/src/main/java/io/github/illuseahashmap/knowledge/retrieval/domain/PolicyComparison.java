package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/** An applicability question has two independent subjects. A missing subject is not a wildcard. */
public record PolicyComparison(String subject, String reference) {
    public enum Side { SUBJECT, REFERENCE }
    // “参照手册填写表格” alone is not a two-policy question.
    private static final Pattern OPERATOR=Pattern.compile("套用|照搬|借用|参照|比照|沿用|(?:能否|是否|可否|能不能)(?:直接)?适用");
    public static Optional<PolicyComparison> parse(String query) {
        var split=OPERATOR.matcher(query);
        if(!split.find()) return Optional.empty();
        if(query.substring(0,split.start()).isBlank() && java.util.Set.of("参照","比照","沿用").contains(split.group()))
            return Optional.empty();
        return Optional.of(new PolicyComparison(query.substring(0,split.start()).strip(),query.substring(split.end()).strip()));
    }
}
