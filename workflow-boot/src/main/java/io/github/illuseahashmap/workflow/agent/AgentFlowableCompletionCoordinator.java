package io.github.illuseahashmap.workflow.agent;

import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import io.github.illuseahashmap.workflow.process.application.AgentCompletionContractException;
import io.github.illuseahashmap.workflow.config.BoundedVirtualThreadTaskExecutor;
import io.github.illuseahashmap.workflow.event.OutboxCommitNotifier;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Claims completion events and dispatches each delivery into an isolated transaction. */
@Component
public class AgentFlowableCompletionCoordinator implements OutboxCommitNotifier {

    private final AgentCompletionEventStore eventStore;
    private final AgentFlowableCompletionProcessor processor;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutor dispatchExecutor;
    private final BoundedVirtualThreadTaskExecutor taskExecutor;
    private final String workerId;
    private final int maxAttempts;
    private final int batchSize;
    private final Duration claimLease;
    private final AtomicBoolean dispatching = new AtomicBoolean();
    private final AtomicBoolean dispatchRequested = new AtomicBoolean();

    public AgentFlowableCompletionCoordinator(
            AgentCompletionEventStore eventStore,
            AgentFlowableCompletionProcessor processor,
            TransactionTemplate transactionTemplate,
            @Qualifier("agentCompletionDispatchTaskExecutor") TaskExecutor dispatchExecutor,
            @Qualifier("agentCompletionTaskExecutor") BoundedVirtualThreadTaskExecutor taskExecutor,
            @Value("${workflow.agent.completion.worker-id:local}") String configuredWorkerId,
            @Value("${workflow.agent.completion.max-attempts:8}") int maxAttempts,
            @Value("${workflow.agent.completion.batch-size:20}") int batchSize,
            @Value("${workflow.agent.completion.claim-lease-seconds:60}") long claimLeaseSeconds
    ) {
        this.eventStore = eventStore;
        this.processor = processor;
        this.transactionTemplate = transactionTemplate;
        this.dispatchExecutor = dispatchExecutor;
        this.taskExecutor = taskExecutor;
        this.workerId = configuredWorkerId + ":" + UUID.randomUUID();
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 100));
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
        this.claimLease = Duration.ofSeconds(Math.max(10, Math.min(claimLeaseSeconds, 3600)));
    }

    @Scheduled(
            fixedDelayString = "${workflow.agent.completion.sweep-interval-ms:${workflow.agent.workflow-resume-interval-ms:1000}}",
            scheduler = "agentCompletionTaskScheduler")
    public void resumeCompletedRuns() {
        requestDispatch();
    }

    @Override
    public void committed(String eventType) {
        if (AgentCompletionEventStore.EVENT_TYPE.equals(eventType)) {
            requestDispatch();
        }
    }

    /** Shared entry point for after-commit, local scheduling and an external XXL recovery trigger. */
    public void requestDispatch() {
        dispatchRequested.set(true);
        if (!dispatching.compareAndSet(false, true)) {
            return;
        }
        try {
            dispatchExecutor.execute(this::drainDispatchRequests);
        } catch (TaskRejectedException exception) {
            dispatching.set(false);
        }
    }

    private void drainDispatchRequests() {
        try {
            while (dispatchRequested.getAndSet(false)) {
                claimAvailableCapacity();
            }
        } finally {
            dispatching.set(false);
            if (dispatchRequested.get()) {
                requestDispatch();
            }
        }
    }

    private void claimAvailableCapacity() {
        int capacity = Math.min(batchSize, taskExecutor.availablePermits());
        if (capacity <= 0) {
            return;
        }
        TrustedDataAccessContext.runAsSystemWorker(() -> {
            for (UUID eventId : eventStore.claim(workerId, capacity, claimLease)) {
                dispatch(eventId);
            }
            return null;
        });
    }

    private void dispatch(UUID eventId) {
        try {
            if (!taskExecutor.tryExecute(() -> process(eventId), this::requestDispatch)) {
                eventStore.release(eventId, workerId, "Completion concurrency limit is exhausted");
            }
        } catch (TaskRejectedException exception) {
            eventStore.release(eventId, workerId, "Completion executor rejected the task");
        }
    }

    private void process(UUID eventId) {
        TrustedDataAccessContext.runAsSystemWorker(() -> {
            try {
                transactionTemplate.executeWithoutResult(status -> processor.process(eventId, workerId));
            } catch (RuntimeException failure) {
                eventStore.retryOrDeadLetter(
                        eventId, workerId, failure, maxAttempts,
                        failure instanceof AgentCompletionContractException);
            }
            return null;
        });
    }
}
