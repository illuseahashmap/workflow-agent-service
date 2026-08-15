package io.github.illuseahashmap.agent.runtime.infrastructure.execution;

import io.github.illuseahashmap.agent.runtime.application.port.AgentRunExecutionRepository;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunLeaseHeartbeat;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRuntimeMetrics;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("agent-lease-heartbeat-", 0).factory());

    public ScheduledAgentRunLeaseHeartbeat(
            AgentRunExecutionRepository repository,
            TransactionTemplate transactionTemplate,
            AgentRuntimeMetrics metrics
    ) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.metrics = metrics;
    }

    @Override
    public LeaseHandle start(LeaseCommand command) {
        AtomicBoolean valid = new AtomicBoolean(true);
        long intervalMillis = Math.max(1_000L, command.leaseDuration().toMillis() / 3L);
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> renew(command, valid), intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return new ScheduledLeaseHandle(valid, future);
    }

    private void renew(LeaseCommand command, AtomicBoolean valid) {
        if (!valid.get()) {
            return;
        }
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
    }

    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
    }

    private record ScheduledLeaseHandle(
            AtomicBoolean valid,
            ScheduledFuture<?> future
    ) implements LeaseHandle {

        @Override
        public boolean isValid() {
            return valid.get();
        }

        @Override
        public void close() {
            future.cancel(false);
        }
    }
}
