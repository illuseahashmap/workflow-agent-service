package io.github.illuseahashmap.workflow.observability;

import io.github.illuseahashmap.agent.runtime.application.port.AgentRuntimeMetrics;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Micrometer adapter for low-cardinality Agent runtime health signals. */
@Component
public class MicrometerAgentRuntimeMetrics implements AgentRuntimeMetrics {

    private final MeterRegistry registry;
    private final Counter claimed;
    private final Counter leaseRenewed;
    private final Counter leaseLost;
    private final Counter recovered;
    private final Counter pauseRequested;
    private final Counter paused;
    private final Counter resumed;
    private final Counter cancelled;

    public MicrometerAgentRuntimeMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.claimed = registry.counter("workflow.agent.runs.claimed");
        this.leaseRenewed = registry.counter("workflow.agent.lease.renewed");
        this.leaseLost = registry.counter("workflow.agent.lease.lost");
        this.recovered = registry.counter("workflow.agent.runs.recovered");
        this.pauseRequested = registry.counter("workflow.agent.runs.pause.requested");
        this.paused = registry.counter("workflow.agent.runs.paused");
        this.resumed = registry.counter("workflow.agent.runs.resumed");
        this.cancelled = registry.counter("workflow.agent.runs.cancelled");
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

    @Override
    public void pauseRequested() {
        pauseRequested.increment();
    }

    @Override
    public void paused() {
        paused.increment();
    }

    @Override
    public void resumed() {
        resumed.increment();
    }

    @Override
    public void cancelled() {
        cancelled.increment();
    }

    @Override
    public void executionDuration(ResultStatus resultStatus, Duration duration) {
        Timer.builder("workflow.agent.runs.duration")
                .tag("result", resultStatus.name())
                .description("End-to-end duration of terminal Agent runs")
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
