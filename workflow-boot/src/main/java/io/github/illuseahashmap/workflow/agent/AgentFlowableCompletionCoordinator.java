package io.github.illuseahashmap.workflow.agent;

import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import io.github.illuseahashmap.workflow.process.application.AgentCompletionContractException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Claims completion events and dispatches each delivery into an isolated transaction. */
@Component
public class AgentFlowableCompletionCoordinator {

    private final AgentCompletionEventStore eventStore;
    private final AgentFlowableCompletionProcessor processor;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutor taskExecutor;
    private final String workerId;
    private final int maxAttempts;
    private final int batchSize;
    private final Duration claimLease;

    public AgentFlowableCompletionCoordinator(
            AgentCompletionEventStore eventStore,
            AgentFlowableCompletionProcessor processor,
            TransactionTemplate transactionTemplate,
            @Qualifier("agentCompletionTaskExecutor") TaskExecutor taskExecutor,
            @Value("${workflow.agent.completion.worker-id:local}") String configuredWorkerId,
            @Value("${workflow.agent.completion.max-attempts:8}") int maxAttempts,
            @Value("${workflow.agent.completion.batch-size:20}") int batchSize,
            @Value("${workflow.agent.completion.claim-lease-seconds:60}") long claimLeaseSeconds
    ) {
        this.eventStore = eventStore;
        this.processor = processor;
        this.transactionTemplate = transactionTemplate;
        this.taskExecutor = taskExecutor;
        this.workerId = configuredWorkerId + ":" + UUID.randomUUID();
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 100));
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
        this.claimLease = Duration.ofSeconds(Math.max(10, Math.min(claimLeaseSeconds, 3600)));
    }

    @Scheduled(
            fixedDelayString = "${workflow.agent.workflow-resume-interval-ms:1000}",
            scheduler = "agentCompletionTaskScheduler")
    public void resumeCompletedRuns() {
        TrustedDataAccessContext.runAsSystemWorker(() -> {
            for (UUID eventId : eventStore.claim(workerId, batchSize, claimLease)) {
                dispatch(eventId);
            }
            return null;
        });
    }

    private void dispatch(UUID eventId) {
        try {
            taskExecutor.execute(() -> process(eventId));
        } catch (TaskRejectedException exception) {
            eventStore.release(eventId, workerId, "Completion executor queue is full");
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
