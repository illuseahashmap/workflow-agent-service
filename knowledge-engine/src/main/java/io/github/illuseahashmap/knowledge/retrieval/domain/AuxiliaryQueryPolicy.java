package io.github.illuseahashmap.knowledge.retrieval.domain;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Conservative topic-only expansion. Conditions stay in the unchanged main query. */
public final class AuxiliaryQueryPolicy {
    public static final String VERSION = "topic-only-v1";
    private static final Pattern NUMERIC = Pattern.compile("[\\p{N}零〇一二三四五六七八九十百千万亿两半壹贰叁肆伍陆柒捌玖拾佰仟萬億兩]");
    private static final Pattern CONDITIONS = Pattern.compile(
            "[<>=≤≥≠~～]|以上|以下|以内|以外|之前|之后|以前|以后|超过|超出|大于|小于|至少|至多|低于|高于|介于|之间|截至"
            + "|不|未|无|非|仅|只|除|禁止|必须|应当|须|需要|如果|否则|若|当|不得|可以|应予|应在|应由|多于|少于|达到|截至|免|所有|全部"
            + "|\\b(?:above|below|over|under|before|after|between|within|outside|at least|at most|more than|less than"
            + "|no|not|never|without|except|excluding|unless|only|must|shall|should|if|required|maximum|minimum|up to|greater than|fewer than"
            + "|cannot|neither|nor|one|two|three|four|five|six|seven|eight|nine|ten|hundred|thousand|million|half|quarter|[a-z]+n['’]t)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private AuxiliaryQueryPolicy() {}

    /** This is a lexical safety boundary, not a semantic equivalence proof. Reject rather than repair. */
    public static boolean isTopicOnly(String query) {
        if (query == null || query.isBlank()) { return false; }
        String normalized = Normalizer.normalize(query, Normalizer.Form.NFKC)
                .replaceAll("\\p{Cf}", "").replaceAll("[\\p{Z}\\s]+", " ")
                .replaceAll("(?<=\\p{IsHan}) +(?=\\p{IsHan})", "");
        return !NUMERIC.matcher(normalized).find() && !CONDITIONS.matcher(normalized).find();
    }
}
