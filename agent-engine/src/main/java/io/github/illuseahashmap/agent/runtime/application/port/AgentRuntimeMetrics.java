package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;

/** Observability port that keeps the Agent application layer independent from Micrometer. */
public interface AgentRuntimeMetrics {

    void claimed();

    void completed(ResultStatus resultStatus);

    void retryScheduled(String errorCode);

    void leaseRenewed();

    void leaseLost();

    void recovered(int count);

    AgentRuntimeMetrics NOOP = new AgentRuntimeMetrics() {
        @Override public void claimed() { }
        @Override public void completed(ResultStatus resultStatus) { }
        @Override public void retryScheduled(String errorCode) { }
        @Override public void leaseRenewed() { }
        @Override public void leaseLost() { }
        @Override public void recovered(int count) { }
    };
}
