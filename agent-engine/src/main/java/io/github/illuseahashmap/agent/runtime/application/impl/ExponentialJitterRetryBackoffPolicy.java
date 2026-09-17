package io.github.illuseahashmap.agent.runtime.application.impl;

import io.github.illuseahashmap.agent.runtime.application.port.AgentRetryBackoffPolicy;
import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Exponential backoff with bounded positive jitter to avoid synchronized retries. */
@Component
public final class ExponentialJitterRetryBackoffPolicy implements AgentRetryBackoffPolicy {

    private static final long DEFAULT_MAX_DELAY_SECONDS = 30L;
    private static final double DEFAULT_JITTER_RATIO = 0.25d;

    private final long maxDelaySeconds;
    private final double jitterRatio;
    private final RandomGenerator random;

    @Autowired
    public ExponentialJitterRetryBackoffPolicy(
            @Value("${workflow.agent.worker.retry.max-delay-seconds:30}") long maxDelaySeconds,
            @Value("${workflow.agent.worker.retry.jitter-ratio:0.25}") double jitterRatio
    ) {
        this(maxDelaySeconds, jitterRatio, RandomGenerator.getDefault());
    }

    ExponentialJitterRetryBackoffPolicy(long maxDelaySeconds, double jitterRatio, RandomGenerator random) {
        this.maxDelaySeconds = Math.max(1L, Math.min(maxDelaySeconds, 3_600L));
        this.jitterRatio = Math.max(0d, Math.min(jitterRatio, 1d));
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    public static ExponentialJitterRetryBackoffPolicy defaults(RandomGenerator random) {
        return new ExponentialJitterRetryBackoffPolicy(
                DEFAULT_MAX_DELAY_SECONDS, DEFAULT_JITTER_RATIO, random);
    }

    @Override
    public Duration delayFor(int attemptNumber) {
        int normalizedAttempt = Math.max(1, Math.min(attemptNumber, 30));
        long exponential = 1L << Math.min(normalizedAttempt - 1, 30);
        long baseSeconds = Math.min(maxDelaySeconds, exponential);
        long jitterBoundMillis = Math.round(baseSeconds * 1_000d * jitterRatio);
        long jitterMillis = jitterBoundMillis == 0
                ? 0L : random.nextLong(jitterBoundMillis + 1L);
        return Duration.ofSeconds(baseSeconds).plusMillis(jitterMillis);
    }
}
