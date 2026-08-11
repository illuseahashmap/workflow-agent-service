package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.agent.runtime.application.AgentRunWorkerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.beans.factory.annotation.Value;

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

    public AgentWorkerSchedulingConfig(
            AgentRunWorkerService workerService,
            @Value("${workflow.agent.worker.execution-pool-size:2}") int executionPoolSize
    ) {
        this.workerService = workerService;
        this.executionPoolSize = Math.max(1, Math.min(executionPoolSize, 16));
    }

    @Bean(name = "agentWorkerTaskScheduler")
    public ThreadPoolTaskScheduler agentWorkerTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(executionPoolSize);
        scheduler.setThreadNamePrefix("agent-worker-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Scheduled(
            fixedDelayString = "${workflow.agent.worker.poll-delay-ms:1000}",
            scheduler = "agentWorkerTaskScheduler")
    public void executeAvailableRuns() {
        for (int index = 0; index < MAX_BATCH_SIZE && workerService.executeNext(); index++) {
            // Bound each polling cycle so web and workflow traffic cannot be starved.
        }
    }

    @Scheduled(
            fixedDelayString = "${workflow.agent.worker.recovery-delay-ms:10000}",
            scheduler = "agentWorkerTaskScheduler")
    public void recoverExpiredRuns() {
        workerService.recoverExpiredRuns();
    }
}
