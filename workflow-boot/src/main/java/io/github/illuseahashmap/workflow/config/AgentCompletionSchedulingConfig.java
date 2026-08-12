package io.github.illuseahashmap.workflow.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Isolates completion polling from Flowable recovery work and other scheduled jobs. */
@Configuration
public class AgentCompletionSchedulingConfig {

    /**
     * Defining a specialized TaskExecutor disables Boot's default executor auto-configuration.
     * Keep the conventional application executor explicit because Flowable requires it by name.
     */
    @Bean(name = "applicationTaskExecutor")
    public AsyncTaskExecutor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("application-task-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Bean(name = "agentCompletionTaskScheduler")
    public ThreadPoolTaskScheduler agentCompletionTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("agent-completion-poll-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Bean(name = "agentCompletionTaskExecutor")
    public TaskExecutor agentCompletionTaskExecutor(
            @Value("${workflow.agent.completion.pool-size:2}") int poolSize,
            @Value("${workflow.agent.completion.queue-capacity:100}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int boundedPoolSize = Math.max(1, Math.min(poolSize, 16));
        executor.setCorePoolSize(boundedPoolSize);
        executor.setMaxPoolSize(boundedPoolSize);
        executor.setQueueCapacity(Math.max(1, Math.min(queueCapacity, 10000)));
        executor.setThreadNamePrefix("agent-completion-work-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
