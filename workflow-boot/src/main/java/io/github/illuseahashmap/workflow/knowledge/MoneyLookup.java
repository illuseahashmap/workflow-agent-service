package io.github.illuseahashmap.workflow.knowledge;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/** CNY point lookup, not a calculator or an inference about the payable total. */
final class MoneyLookup {
    static final String NUMBER = "(?:[0-9]{1,3}(?:,[0-9]{3}){1,5}|[0-9]{1,16})(?:\\.[0-9]{1,8})?";
    private static final Pattern AMOUNT = Pattern.compile("(?<![0-9.,+\\-负])("+NUMBER+")\\s*(万?)元");
    private static final Pattern NON_POINT = Pattern.compile(
            "未知|不明|未确定|另需|另加|不含税|超过|超出|高于|大于|小于|低于|不足|以上|以下|以内|至少|至多|不满|[<>≤≥~～至]|美元|港元|日元|欧元|美金|港币|外币|约为|大约|左右|[负−+\\-]\\s*\\d",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OTHER_NUMBER = Pattern.compile("[0-9]");
    static boolean mentionsAmount(String query) { return AMOUNT.matcher(query).find(); }
    static Optional<BigDecimal> point(String query) {
        if (query == null || query.length()>4096) return Optional.empty();
        // Missing classification is independent of a known total; unknown charges still veto point lookup.
        String amountContext=query.replaceAll("(?:专业|类别|类型|归属)(?:尚未确定|未确定|不明|未知)", "");
        if (NON_POINT.matcher(amountContext).find()) return Optional.empty();
        var m=AMOUNT.matcher(query);
        if (!m.find() || OTHER_NUMBER.matcher(query.substring(0,m.start())+query.substring(m.end())).find())
            return Optional.empty();
        return Optional.of(decimal(m.group(1)).scaleByPowerOfTen(m.group(2).isEmpty()?0:4));
    }
    static BigDecimal decimal(String value) { return new BigDecimal(value.replace(",","")); }
    private MoneyLookup() {}
}
