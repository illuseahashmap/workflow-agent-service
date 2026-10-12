package io.github.illuseahashmap.agent.runtime.application.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CheckpointContextPolicyTest {

    @Test
    void compactionPreservesImmutablePrefixLatestEvidenceAndDigest() {
        CheckpointContextPolicy policy = new CheckpointContextPolicy(4_096);
        String original = "immutable-input:" + "a".repeat(3_000)
                + "discardable-history:" + "b".repeat(3_000)
                + "latest-tool-result:" + "c".repeat(3_000);

        String compacted = policy.compact(original);

        assertThat(compacted).hasSize(4_096)
                .startsWith("immutable-input:")
                .contains("checkpoint history omitted sha256=")
                .endsWith("c".repeat(1_000));
        assertThat(policy.matchesDigest(compacted, policy.digest(compacted))).isTrue();
    }

    @Test
    void externalizesLargeToolResultsWithoutSplittingUnicodePairs() {
        CheckpointContextPolicy policy = new CheckpointContextPolicy(4_096, 257);
        String output = "😀".repeat(300);

        String context = policy.appendToolResult("request", "directory", output, "audit-key-1");

        assertThat(context).contains("权威引用 audit-key-1", "tool result externalized",
                "sha256=" + policy.digest(output));
        assertThat(context).doesNotContain("\uFFFD");
        assertThat(context.codePoints().filter(codePoint -> codePoint == 0x1F600).count())
                .isLessThan(300);
    }
}
