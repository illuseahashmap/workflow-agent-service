package io.github.illuseahashmap.workflow.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresKeywordChunkSearchAdapterTest {

    @Test
    void derivesBoundedCjkBigramsForPortableLexicalFallback() {
        var terms = PostgresKeywordChunkSearchAdapter.fallbackTerms(
                "普通办公采购需要财务复核和采购委员会审批");

        assertThat(terms)
                .contains("普通", "办公", "采购", "财务", "复核", "委员")
                .hasSizeLessThanOrEqualTo(16);
    }

    @Test
    void preservesLatinTermsAndIgnoresBlankQueries() {
        assertThat(PostgresKeywordChunkSearchAdapter.fallbackTerms("budget POLICY-2026"))
                .containsExactly("budget", "policy-2026");
        assertThat(PostgresKeywordChunkSearchAdapter.fallbackTerms("  ")).isEmpty();
    }
}
