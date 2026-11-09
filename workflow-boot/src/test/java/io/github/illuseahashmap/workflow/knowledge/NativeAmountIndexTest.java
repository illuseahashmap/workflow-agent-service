package io.github.illuseahashmap.workflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.illuseahashmap.workflow.knowledge.NativeDocumentParser.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeAmountIndexTest {
    @Test void onlyOneExplicitCnyPoint() {
        assertThat(MoneyLookup.point("含税总额 2,000.01 元").orElseThrow()).isEqualByComparingTo("2000.01");
        assertThat(MoneyLookup.point("金额 2 万元").orElseThrow()).isEqualByComparingTo("20000");
        assertThat(MoneyLookup.point("运维费用 600 元，专业未知").orElseThrow()).isEqualByComparingTo("600");
        assertThat(MoneyLookup.point("专业未知且安装费不明，报价 600 元")).isEmpty();
        var comparison=io.github.illuseahashmap.knowledge.retrieval.domain.PolicyComparison.parse("内勤业务能否套用野外作业管理规则？").orElseThrow();
        assertThat(comparison.subject()).contains("内勤业务");
        assertThat(comparison.reference()).contains("野外作业").doesNotContain("内勤");
        assertThat(NativeTableScope.assertsCategory("尚未确定是否属于水下作业")).isFalse();
        for(String q:List.of("1,900 元，安装费未知","2 万元而非 2 元","超过 20,000 元","- 1 元",
                "港币 300 元","20,00.01 元","金额 2.3.4 元","5 美元","没有金额")) assertThat(MoneyLookup.point(q)).as(q).isEmpty();
    }
    @Test void intervalEndsAndQualifierComeFromOneTableAndUniqueText() {
        var text="""
                |类别|费用金额 X（万元）|处理|
                |---|---|---|
                |普通|1 ≤ X < 2|登记|
                |大额|X ≥ 2|必要时提请专家复核。|

                大额不一律提请专家复核。
                """;
        var m=NativeDocumentParser.parse("MD",text.getBytes(StandardCharsets.UTF_8));
        var entries=NativeAmountIndex.project(m);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).bounds().literal()).isEqualTo("[10000,20000)");
        assertThat(entries.get(2).bounds().literal()).isEqualTo("[20000,)");
        assertThat(entries.get(2).header()).isZero();
        assertThat(entries.get(2).qualifier()).isEqualTo(3);
        var two=NativeDocumentParser.parse("MD",(text+"\n大额另有提请专家复核的说明。").getBytes(StandardCharsets.UTF_8));
        assertThat(NativeAmountIndex.project(two).get(2).qualifier()).isNull();
        var foreign=NativeDocumentParser.parse("MD",text.replace("万元","美元").getBytes(StandardCharsets.UTF_8));
        assertThat(NativeAmountIndex.project(foreign)).isEmpty();
        assertThat(NativeAmountProjectionStore.samePositions(m,foreign)).isFalse();
    }
    @Test void singleWorkbookCellCannotForgeHeaderAndImplicitOrRoundedValuesAreNotBounds() {
        var h=row(1,"A1: 类别 | B1: 金额下限（不含，元） | C1: 金额上限（含，元）",3);
        var r=row(2,"A2: 常规 | B2: 0 | C2: 500",3);
        assertThat(project(h,r)).hasSize(1);
        assertThat(project(row(1,h.text(),1),r)).isEmpty();
        assertThat(project(h,row(2,r.text()+" [raw=500.49]",3))).isEmpty();
        assertThat(project(h,row(2,"A2: 常规 | C2: 500",2))).isEmpty();
        assertThat(project(h,row(2,r.text(),1))).isEmpty();
    }
    private static Block row(int n,String text,int width) {
        return new Block(new NativeLocator(2,NativeLocator.Kind.XLSX_CELLS,"金额表","A"+n,"C"+n,null),text,width);
    }
    private static java.util.Map<Integer,NativeAmountIndex.Entry> project(Block h,Block r) {
        return NativeAmountIndex.project(new Manifest("PARSED","xssf-visible-rows-v2",List.of(h,r),List.of()));
    }
}
