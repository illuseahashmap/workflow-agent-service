package io.github.illuseahashmap.knowledge.retrieval.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalProfileVersionTest {
    @Test
    void rejectsUnboundedProfileResults() {
        assertThatThrownBy(() -> new RetrievalProfileVersion(
                1, "tenant-a", "policies", 1, RetrievalRequest.StrategyHint.KEYWORD,
                51, 0, 2, "PUBLISHED", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxResults");
    }
}
