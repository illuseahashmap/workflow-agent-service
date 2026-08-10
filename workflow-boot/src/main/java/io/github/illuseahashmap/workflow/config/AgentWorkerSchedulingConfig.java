package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.agent.runtime.application.AgentRunWorkerService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

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

    public AgentWorkerSchedulingConfig(AgentRunWorkerService workerService) {
        this.workerService = workerService;
    }

    @Scheduled(fixedDelayString = "${workflow.agent.worker.poll-delay-ms:1000}")
    public void executeAvailableRuns() {
        for (int index = 0; index < MAX_BATCH_SIZE && workerService.executeNext(); index++) {
            // Bound each polling cycle so web and workflow traffic cannot be starved.
        }
    }
}
