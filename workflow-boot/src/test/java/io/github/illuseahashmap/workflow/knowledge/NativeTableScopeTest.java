package io.github.illuseahashmap.workflow.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NativeTableScopeTest {
    @Test void aliasesRequireAnExactCaptionAndACompleteAffirmativePhrase() {
        assertThat(NativeScopeVocabulary.headerKeys("样品冷链寄送"))
                .contains("标本低温发运").doesNotContain("低温","发运","标本");
        assertThat(NativeScopeVocabulary.headerKeys("食品冷链寄送")).doesNotContain("标本低温发运");
        assertThat(NativeTableScope.queryKeys("并非标本低温发运，而是常温寄送")).doesNotContain("标本低温发运");
    }
    @Test void explicitNegationIsNotAnAffirmativeTableName() {
        assertThat(NativeTableScope.queryKeys("不是年度检修，而是临时外协，金额 6200 元"))
                .contains("临时外协").doesNotContain("年度检修");
        assertThat(io.github.illuseahashmap.knowledge.retrieval.domain.PolicyComparison.parse("跨区作业规则是否适用？")
                .orElseThrow().subject()).isEqualTo("跨区作业规则");
    }

    @Test void unrelatedTablesHaveIndependentDirectoriesAndNotes() {
        var text="""
                ## 野外巡检
                巡检按单次作业金额分档。

                | 类别 | 金额 M（元） | 处理 |
                | --- | --- | --- |
                | 岩层 | M > 0 | 交甲组。 |

                无法选行时退回补录类别。

                ## 水下测绘
                | 类别 | 金额 M（元） | 处理 |
                | --- | --- | --- |
                | 水域 | M > 0 | 交乙组。 |
                """;
        var m=NativeDocumentParser.parse("MD",text.getBytes(StandardCharsets.UTF_8));
        var scopes=NativeAmountIndex.project(m).values().stream()
                .sorted(Comparator.comparingInt(NativeAmountIndex.Entry::row)).map(NativeAmountIndex.Entry::scope).toList();
        assertThat(scopes).hasSize(2);
        var first=scopes.getFirst();
        assertThat(first.keys()).contains("野外巡检").doesNotContain("水下");
        assertThat(m.blocks().get(first.note()).text()).contains("补录类别");
        assertThat(m.blocks().get(first.basis()).text()).contains("单次");
        assertThat(scopes.getLast().keys()).contains("水下测绘").doesNotContain("巡检");
        assertThat(scopes.getLast().note()).isNull();
        assertThat(scopes.getLast().basis()).isNull();
    }
}
