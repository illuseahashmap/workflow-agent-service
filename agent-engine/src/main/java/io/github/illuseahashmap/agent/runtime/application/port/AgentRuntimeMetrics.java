package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import java.time.Duration;

/** Observability port that keeps the Agent application layer independent from Micrometer. */
public interface AgentRuntimeMetrics {

    void claimed();

    void completed(ResultStatus resultStatus);

    void retryScheduled(String errorCode);

    void leaseRenewed();

    void leaseLost();

    void recovered(int count);

    default void leaseRenewalDelay(Duration delay) { }

    default void leaseRenewalDuration(Duration duration) { }

    default void heartbeatQueueDepth(int depth) { }

    default void heartbeatActiveRegistrations(int count) { }

    default void heartbeatRegistrationCapacity(int capacity) { }

    default void pauseRequested() { }

    default void paused() { }

    default void resumed() { }

    default void cancelled() { }

    default void executionDuration(ResultStatus resultStatus, Duration duration) { }

    AgentRuntimeMetrics NOOP = new AgentRuntimeMetrics() {
        @Override public void claimed() { }
        @Override public void completed(ResultStatus resultStatus) { }
        @Override public void retryScheduled(String errorCode) { }
        @Override public void leaseRenewed() { }
        @Override public void leaseLost() { }
        @Override public void recovered(int count) { }
    };
}
