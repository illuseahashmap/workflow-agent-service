package io.github.illuseahashmap.agent.runtime.application.port;

import java.time.Duration;

/** Calculates the persisted delay before a retry becomes available. */
@FunctionalInterface
public interface AgentRetryBackoffPolicy {

    Duration delayFor(int attemptNumber);
}
