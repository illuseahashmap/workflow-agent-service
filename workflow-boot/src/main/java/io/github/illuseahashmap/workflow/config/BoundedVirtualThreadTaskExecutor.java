package io.github.illuseahashmap.workflow.config;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

/** Virtual-thread executor with an explicit concurrency boundary and no hidden queue. */
public final class BoundedVirtualThreadTaskExecutor implements TaskExecutor, AutoCloseable {

    private final Semaphore permits;
    private final ExecutorService executor;
    private final Duration shutdownTimeout;
    private final int maximumConcurrency;

    public BoundedVirtualThreadTaskExecutor(int concurrency, String threadNamePrefix, Duration shutdownTimeout) {
        int boundedConcurrency = Math.max(1, Math.min(concurrency, 256));
        this.maximumConcurrency = boundedConcurrency;
        this.permits = new Semaphore(boundedConcurrency);
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(threadNamePrefix, 0).factory());
        this.shutdownTimeout = shutdownTimeout;
    }

    @Override
    public void execute(Runnable task) {
        if (!tryExecute(task)) {
            throw new TaskRejectedException("Virtual-thread concurrency limit is exhausted");
        }
    }

    public boolean tryExecute(Runnable task) {
        return tryExecute(task, () -> { });
    }

    public boolean tryExecute(Runnable task, Runnable afterCompletion) {
        if (!permits.tryAcquire()) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    permits.release();
                    afterCompletion.run();
                }
            });
            return true;
        } catch (RuntimeException exception) {
            permits.release();
            throw exception;
        }
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    public int activeTasks() {
        return maximumConcurrency - permits.availablePermits();
    }

    public int maximumConcurrency() {
        return maximumConcurrency;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeout.toSeconds(), TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
