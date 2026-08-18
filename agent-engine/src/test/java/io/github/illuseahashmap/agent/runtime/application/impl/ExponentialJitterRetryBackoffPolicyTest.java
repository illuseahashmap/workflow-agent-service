package io.github.illuseahashmap.agent.runtime.application.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class ExponentialJitterRetryBackoffPolicyTest {

    @Test
    void keepsExponentialBaseAndAddsBoundedJitter() {
        RandomGenerator random = new RandomGenerator() {
            @Override public long nextLong(long bound) { return bound - 1; }
            @Override public long nextLong() { return 0; }
        };
        var policy = ExponentialJitterRetryBackoffPolicy.defaults(random);

        assertThat(policy.delayFor(1).toMillis()).isEqualTo(1_250L);
        assertThat(policy.delayFor(2).toMillis()).isEqualTo(2_500L);
        assertThat(policy.delayFor(6).toMillis()).isEqualTo(37_500L);
    }

    @Test
    void capsBaseDelayWithoutRemovingJitter() {
        RandomGenerator random = new RandomGenerator() {
            @Override public long nextLong(long bound) { return 0; }
            @Override public long nextLong() { return 0; }
        };
        var policy = ExponentialJitterRetryBackoffPolicy.defaults(random);

        assertThat(policy.delayFor(20).toSeconds()).isEqualTo(30L);
        assertThat(policy.delayFor(20).toMillis()).isEqualTo(30_000L);
    }
}
