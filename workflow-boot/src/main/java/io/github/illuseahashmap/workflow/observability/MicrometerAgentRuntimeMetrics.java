package io.github.illuseahashmap.workflow.observability;

import io.github.illuseahashmap.agent.runtime.application.port.AgentRuntimeMetrics;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Micrometer adapter for low-cardinality Agent runtime health signals. */
@Component
public class MicrometerAgentRuntimeMetrics implements AgentRuntimeMetrics {

    private final MeterRegistry registry;
    private final Counter claimed;
    private final Counter leaseRenewed;
    private final Counter leaseLost;
    private final Counter recovered;

    public MicrometerAgentRuntimeMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.claimed = registry.counter("workflow.agent.runs.claimed");
        this.leaseRenewed = registry.counter("workflow.agent.lease.renewed");
        this.leaseLost = registry.counter("workflow.agent.lease.lost");
        this.recovered = registry.counter("workflow.agent.runs.recovered");
    }

    @Override
    public void claimed() {
        claimed.increment();
    }

    @Override
    public void completed(ResultStatus resultStatus) {
        registry.counter("workflow.agent.runs.completed", "result", resultStatus.name()).increment();
    }

    @Override
    public void retryScheduled(String errorCode) {
        registry.counter("workflow.agent.runs.retry.scheduled", "error", normalize(errorCode)).increment();
    }

    @Override
    public void leaseRenewed() {
        leaseRenewed.increment();
    }

    @Override
    public void leaseLost() {
        leaseLost.increment();
    }

    @Override
    public void recovered(int count) {
        if (count > 0) {
            recovered.increment(count);
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
