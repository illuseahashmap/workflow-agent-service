package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/** An applicability question has two independent subjects. A missing subject is not a wildcard. */
public record PolicyComparison(String subject, String reference) {
    public enum Side {
        SUBJECT,
        REFERENCE
    }

    // “参照手册填写表格” alone is not a two-policy question.
    private static final Pattern OPERATOR =
            Pattern.compile("套用|照搬|借用|参照|比照|沿用|(?:能否|是否|可否|能不能)(?:直接)?适用");

    public static Optional<PolicyComparison> parse(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        var split = OPERATOR.matcher(query);
        while (split.find()) {
            String subject = query.substring(0, split.start()).strip();
            String reference = query.substring(split.end()).strip();
            if (subject.isBlank() || reference.isBlank()) {
                continue;
            }
            // Borrowing is also an ordinary business noun/verb. Only route an explicit
            // applicability question about a normative reference, never an equipment loan.
            if ("借用".equals(split.group())
                    && !(subject.matches("(?s).*(?:能否|是否|可否|能不能|可以|可不可以)(?:直接)?$")
                            && reference.matches("(?s).*(?:制度|政策|规则|规定|办法|标准|流程|条件|口径|要求).*"))) {
                continue;
            }
            return Optional.of(new PolicyComparison(subject, reference));
        }
        return Optional.empty();
    }
}
