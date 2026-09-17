package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.agent.runtime.application.AgentRunWorkerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
        prefix = "workflow.agent.worker",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AgentWorkerSchedulingConfig {

    private static final int MAX_BATCH_SIZE = 5;

    private final AgentRunWorkerService workerService;
    private final int executionPoolSize;
    private final int queueCapacity;

    public AgentWorkerSchedulingConfig(
            AgentRunWorkerService workerService,
            @Value("${workflow.agent.worker.execution-pool-size:2}") int executionPoolSize,
            @Value("${workflow.agent.worker.queue-capacity:20}") int queueCapacity
    ) {
        this.workerService = workerService;
        this.executionPoolSize = Math.max(1, Math.min(executionPoolSize, 16));
        this.queueCapacity = Math.max(1, Math.min(queueCapacity, 1000));
    }

    @Bean(name = "agentWorkerTaskScheduler")
    public ThreadPoolTaskScheduler agentWorkerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("agent-worker-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Bean(name = "agentModelExecutionTaskExecutor")
    public ThreadPoolTaskExecutor agentModelExecutionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(executionPoolSize);
        executor.setMaxPoolSize(executionPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("agent-model-execution-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Scheduled(
            fixedDelayString = "${workflow.agent.worker.poll-delay-ms:1000}",
            scheduler = "agentWorkerTaskScheduler")
    public void executeAvailableRuns() {
        for (int index = 0; index < executionPoolSize; index++) {
            try {
                agentModelExecutionTaskExecutor().execute(this::executeBoundedBatch);
            } catch (TaskRejectedException ignored) {
                // A bounded queue is an intentional backpressure boundary; the next poll retries.
                break;
            }
        }
    }

    private void executeBoundedBatch() {
        for (int index = 0; index < MAX_BATCH_SIZE && workerService.executeNext(); index++) {
            // Each executor slot drains a bounded batch before yielding to the next polling cycle.
        }
    }

    @Scheduled(
            fixedDelayString = "${workflow.agent.worker.recovery-delay-ms:10000}",
            scheduler = "agentWorkerTaskScheduler")
    public void recoverExpiredRuns() {
        workerService.recoverExpiredRuns();
    }
}
