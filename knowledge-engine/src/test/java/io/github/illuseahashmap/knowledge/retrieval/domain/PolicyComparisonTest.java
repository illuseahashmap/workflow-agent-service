package io.github.illuseahashmap.knowledge.retrieval.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PolicyComparisonTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "关于物资借用：借用记录用什么区别两台同型号投影仪？",
                "这个部门能否借用另一部门的相机？",
                "借用记录的编号在哪里？",
                "参照手册填写表格",
                "套用",
                "采购套用",
                ""
            })
    void ordinaryQueriesAreNotPolicyComparisons(String query) {
        assertThat(PolicyComparison.parse(query)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "内部交接能否套用外部运输制度",
                "采购可否套用检修制度",
                "内勤业务能否借用野外作业管理规则？",
                "借用设备的审批能否套用采购审批制度？",
                "设备维护是否直接适用运输标准？"
            })
    void explicitPolicyQuestionsKeepBothSubjects(String query) {
        var parsed = PolicyComparison.parse(query).orElseThrow();
        assertThat(parsed.subject()).isNotBlank();
        assertThat(parsed.reference()).isNotBlank();
    }
}
