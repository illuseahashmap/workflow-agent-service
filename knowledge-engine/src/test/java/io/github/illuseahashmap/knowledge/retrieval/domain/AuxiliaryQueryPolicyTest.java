package io.github.illuseahashmap.knowledge.retrieval.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class AuxiliaryQueryPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"采购金额1500元以下", "付款不超过8500元", "交付期 ≥ 30天", "预算５０００以内", "质量评分 ≤ 七十分",
            "超过三万元审批", "审批人仅主管", "不需要联合复核", "未成年人办理", "非员工不可使用", "如果加急则经理审批",
            "amount below USD 900", "at least five approvals", "not eligible", "employees only", "without supervisor",
            "before June", "x < y", "no approval required", "权限范围以外", "有效日期2027年", "金额以 下", "金额以\u200b下", "amount up   to ceiling",
            "doesn't require approval", "five approvals", "伍万元"})
    void rejectsConditionsRatherThanGuessingTheirMeaning(String query) { assertThat(AuxiliaryQueryPolicy.isTopicOnly(query)).isFalse(); }

    @ParameterizedTest
    @ValueSource(strings = {"普通办公采购 审批权限 金额标准", "交付期限 责任划分 政策", "休假申请 资格条件", "设备保养 周期标准",
            "travel reimbursement policy", "payment authorization amount bands"})
    void acceptsTopicalRetrievalVocabulary(String query) { assertThat(AuxiliaryQueryPolicy.isTopicOnly(query)).isTrue(); }
}
