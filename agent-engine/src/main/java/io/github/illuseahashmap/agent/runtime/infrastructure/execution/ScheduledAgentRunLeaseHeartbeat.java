package io.github.illuseahashmap.agent.runtime.infrastructure.execution;

import io.github.illuseahashmap.agent.runtime.application.port.AgentRunExecutionRepository;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunLeaseHeartbeat;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRuntimeMetrics;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Renews leases on a dedicated bounded scheduler so blocking Provider calls cannot starve heartbeats. */
@Component
public class ScheduledAgentRunLeaseHeartbeat implements AgentRunLeaseHeartbeat {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledAgentRunLeaseHeartbeat.class);

    private final AgentRunExecutionRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final AgentRuntimeMetrics metrics;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Semaphore registrationCapacity;
    private final int maximumTrackedRuns;

    @Autowired
    public ScheduledAgentRunLeaseHeartbeat(
            AgentRunExecutionRepository repository,
            TransactionTemplate transactionTemplate,
            AgentRuntimeMetrics metrics,
            @Value("${workflow.agent.worker.heartbeat-pool-size:4}") int poolSize,
            @Value("${workflow.agent.worker.heartbeat-maximum-tracked-runs:1024}") int maximumTrackedRuns
    ) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
        int boundedPoolSize = Math.max(1, Math.min(poolSize, 32));
        this.maximumTrackedRuns = Math.max(boundedPoolSize, Math.min(maximumTrackedRuns, 100_000));
        this.registrationCapacity = new Semaphore(this.maximumTrackedRuns);
        this.scheduler = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(
                boundedPoolSize, Thread.ofPlatform().name("agent-lease-heartbeat-", 0).factory());
        this.scheduler.setRemoveOnCancelPolicy(true);
        this.scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.metrics.heartbeatRegistrationCapacity(this.maximumTrackedRuns);
    }

    public ScheduledAgentRunLeaseHeartbeat(
            AgentRunExecutionRepository repository,
            TransactionTemplate transactionTemplate,
            AgentRuntimeMetrics metrics
    ) {
        this(repository, transactionTemplate, metrics, 4, 1024);
    }

    ScheduledAgentRunLeaseHeartbeat(
            AgentRunExecutionRepository repository,
            TransactionTemplate transactionTemplate,
            AgentRuntimeMetrics metrics,
            int poolSize
    ) {
        this(repository, transactionTemplate, metrics, poolSize, 1024);
    }

    @Override
    public LeaseHandle start(LeaseCommand command) {
        if (!registrationCapacity.tryAcquire()) {
            throw new IllegalStateException("Agent lease heartbeat capacity exhausted");
        }
        AtomicBoolean valid = new AtomicBoolean(true);
        long intervalMillis = Math.max(1_000L, command.leaseDuration().toMillis() / 3L);
        AtomicLong expectedAtNanos = new AtomicLong(System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(intervalMillis));
        try {
            ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                    () -> renew(command, valid, expectedAtNanos, intervalMillis),
                    intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
            publishSchedulerSize();
            return new ScheduledLeaseHandle(valid, future);
        } catch (RuntimeException exception) {
            registrationCapacity.release();
            publishSchedulerSize();
            throw exception;
        }
    }

    private void renew(LeaseCommand command, AtomicBoolean valid,
                       AtomicLong expectedAtNanos, long intervalMillis) {
        if (!valid.get()) {
            return;
        }
        long startedNanos = System.nanoTime();
        long expectedNanos = expectedAtNanos.getAndAdd(TimeUnit.MILLISECONDS.toNanos(intervalMillis));
        metrics.leaseRenewalDelay(Duration.ofNanos(Math.max(0L, startedNanos - expectedNanos)));
        publishSchedulerSize();
        Instant now = Instant.now();
        if (!now.isBefore(command.deadlineAt())) {
            valid.set(false);
            metrics.leaseLost();
            return;
        }
        Instant newExpiry = now.plus(command.leaseDuration());
        if (newExpiry.isAfter(command.deadlineAt())) {
            newExpiry = command.deadlineAt();
        }
        Instant leaseExpiresAt = newExpiry;
        boolean renewed;
        try {
            renewed = TrustedDataAccessContext.runAsSystemWorker(() -> Boolean.TRUE.equals(
                    transactionTemplate.execute(status -> repository.renewLease(
                            command.tenantCode(), command.runId(), command.attemptId(),
                            command.leaseOwner(), now, leaseExpiresAt))));
        } catch (RuntimeException exception) {
            LOGGER.warn("Agent lease renewal failed for run {} attempt {}",
                    command.runId(), command.attemptId(), exception);
            renewed = false;
        }
        valid.set(renewed);
        if (renewed) {
            metrics.leaseRenewed();
        } else {
            metrics.leaseLost();
        }
        metrics.leaseRenewalDuration(Duration.ofNanos(System.nanoTime() - startedNanos));
    }

    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
        metrics.heartbeatQueueDepth(0);
        metrics.heartbeatActiveRegistrations(0);
    }

    private void publishSchedulerSize() {
        metrics.heartbeatQueueDepth(scheduler.getQueue().size());
        metrics.heartbeatActiveRegistrations(maximumTrackedRuns - registrationCapacity.availablePermits());
    }

    private final class ScheduledLeaseHandle implements LeaseHandle {

        private final AtomicBoolean valid;
        private final ScheduledFuture<?> future;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ScheduledLeaseHandle(AtomicBoolean valid, ScheduledFuture<?> future) {
            this.valid = valid;
            this.future = future;
        }

        @Override
        public boolean isValid() {
            return valid.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                valid.set(false);
                future.cancel(false);
                registrationCapacity.release();
                publishSchedulerSize();
            }
        }
    }
}
