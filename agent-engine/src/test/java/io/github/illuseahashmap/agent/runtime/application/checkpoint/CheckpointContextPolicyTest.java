package io.github.illuseahashmap.agent.runtime.application.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CheckpointContextPolicyTest {

    @Test
    void refusesAnOversizedFinalRequestInsteadOfDroppingAuthoritativeInput() {
        var policy = new CheckpointContextPolicy(4096);
        policy.requireFits(4096);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> policy.requireFits(4097))
                .hasMessageContaining("context budget");
    }

    @Test
    void externalizesLargeToolResultsWithoutSplittingUnicodePairs() {
        CheckpointContextPolicy policy = new CheckpointContextPolicy(4_096, 257);
        String output = "😀".repeat(300);

        String context = policy.preview(output);

        assertThat(context).contains("外置",
                "sha256=" + policy.digest(output));
        assertThat(context).doesNotContain("\uFFFD");
        assertThat(context.codePoints().filter(codePoint -> codePoint == 0x1F600).count())
                .isLessThan(300);
    }
}
