package io.github.illuseahashmap.workflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostgresKeywordChunkSearchAdapterTest {

    @Test
    void derivesBoundedCjkBigramsForPortableLexicalFallback() {
        var terms = PostgresKeywordChunkSearchAdapter.fallbackTerms("普通办公采购需要财务复核和采购委员会审批");

        assertThat(terms)
                .contains("普通", "办公", "采购", "财务", "复核", "委员")
                .hasSizeLessThanOrEqualTo(128);
    }

    @Test
    void preservesLatinTermsAndIgnoresBlankQueries() {
        assertThat(PostgresKeywordChunkSearchAdapter.fallbackTerms("budget POLICY-2026"))
                .containsExactly("budget", "policy-2026");
        assertThat(PostgresKeywordChunkSearchAdapter.fallbackTerms("  ")).isEmpty();
    }

    @Test
    void keepsTheQuestionTailAndSamplesOversizeQueriesAcrossTheirFullLength() {
        var query = new StringBuilder("opening ");
        for (int i = 0; i < 400; i++) {
            query.append("token").append(i).append(' ');
        }
        query.append("closing");
        var terms = PostgresKeywordChunkSearchAdapter.fallbackTerms(query.toString());
        assertThat(terms).hasSize(128).contains("opening", "closing");
        assertThat(terms).anyMatch(t -> t.matches("token2[0-9]{2}"));
        assertThat(
                        PostgresKeywordChunkSearchAdapter.fallbackTerms(
                                "请结合当前资料说明具体处理办法以及相关限制最后核对压力传感器"))
                .contains("压力", "传感", "感器");
    }
}
