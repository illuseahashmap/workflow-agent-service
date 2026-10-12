package io.github.illuseahashmap.workflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MicrometerAgentRuntimeMetricsTest {

    @Test
    void recordsRuntimeCountersTimersAndBoundedQueueDepth() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerAgentRuntimeMetrics metrics = new MicrometerAgentRuntimeMetrics(registry);

        metrics.claimed();
        metrics.completed(ResultStatus.SUCCESS);
        metrics.retryScheduled(null);
        metrics.leaseRenewed();
        metrics.leaseLost();
        metrics.recovered(0);
        metrics.recovered(2);
        metrics.pauseRequested();
        metrics.paused();
        metrics.resumed();
        metrics.cancelled();
        metrics.executionDuration(ResultStatus.SUCCESS, Duration.ofMillis(25));
        metrics.leaseRenewalDelay(Duration.ofMillis(5));
        metrics.leaseRenewalDuration(Duration.ofMillis(8));
        metrics.heartbeatQueueDepth(-1);
        metrics.heartbeatActiveRegistrations(3);
        metrics.heartbeatRegistrationCapacity(8);

        assertThat(registry.get("workflow.agent.runs.claimed").counter().count()).isEqualTo(1);
        assertThat(registry.get("workflow.agent.runs.completed").tag("result", "SUCCESS").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("workflow.agent.runs.retry.scheduled").tag("error", "UNKNOWN").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("workflow.agent.lease.renewed").counter().count()).isEqualTo(1);
        assertThat(registry.get("workflow.agent.lease.lost").counter().count()).isEqualTo(1);
        assertThat(registry.get("workflow.agent.runs.recovered").counter().count()).isEqualTo(2);
        assertThat(registry.get("workflow.agent.runs.pause.requested").counter().count()).isEqualTo(1);
        assertThat(registry.get("workflow.agent.runs.paused").counter().count()).isEqualTo(1);
        assertThat(registry.get("workflow.agent.runs.resumed").counter().count()).isEqualTo(1);
        assertThat(registry.get("workflow.agent.runs.cancelled").counter().count()).isEqualTo(1);
        assertThat(registry.get("workflow.agent.runs.duration").tag("result", "SUCCESS").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("workflow.agent.lease.renewal.delay").timer().count()).isEqualTo(1);
        assertThat(registry.get("workflow.agent.lease.renewal.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("workflow.agent.heartbeat.queue.depth").gauge().value()).isZero();
        assertThat(registry.get("workflow.agent.heartbeat.active.registrations").gauge().value()).isEqualTo(3);
        assertThat(registry.get("workflow.agent.heartbeat.registration.capacity").gauge().value()).isEqualTo(8);

        metrics.heartbeatQueueDepth(3);
        assertThat(registry.get("workflow.agent.heartbeat.queue.depth").gauge().value()).isEqualTo(3);
    }
}
