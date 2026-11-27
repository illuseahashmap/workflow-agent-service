package io.github.illuseahashmap.workflow.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
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

    @Bean(name = "agentCompletionDispatchTaskExecutor")
    public TaskExecutor agentCompletionDispatchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("agent-completion-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Bean(name = "agentCompletionTaskExecutor", destroyMethod = "close")
    public BoundedVirtualThreadTaskExecutor agentCompletionTaskExecutor(
            @Value("${workflow.agent.completion.max-concurrency:8}") int maxConcurrency,
            MeterRegistry meterRegistry
    ) {
        BoundedVirtualThreadTaskExecutor executor = new BoundedVirtualThreadTaskExecutor(
                maxConcurrency, "agent-completion-vt-", Duration.ofSeconds(30));
        Gauge.builder("workflow.agent.outbox.completion.active", executor,
                        BoundedVirtualThreadTaskExecutor::activeTasks)
                .description("Active Agent completion Outbox virtual-thread tasks")
                .register(meterRegistry);
        Gauge.builder("workflow.agent.outbox.completion.capacity", executor,
                        BoundedVirtualThreadTaskExecutor::maximumConcurrency)
                .description("Maximum concurrent Agent completion Outbox tasks")
                .register(meterRegistry);
        return executor;
    }
}
