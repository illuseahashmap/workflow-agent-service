package io.github.illuseahashmap.workflow.process.infrastructure.lock;

import io.github.illuseahashmap.workflow.process.application.port.ProcessInstanceLock;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class RedisProcessInstanceLock implements ProcessInstanceLock {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisProcessInstanceLock.class);
    private static final long RETRY_INTERVAL_MILLIS = 100L;
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final WorkflowLockProperties properties;
    private final ScheduledExecutorService renewalExecutor;

    public RedisProcessInstanceLock(StringRedisTemplate redisTemplate, WorkflowLockProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.renewalExecutor = Executors.newScheduledThreadPool(
                Math.max(1, properties.getRenewalThreads()), runnable -> {
                    Thread thread = new Thread(runnable, "workflow-lock-renewal");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Override
    public <T> T execute(String processInstanceId, Supplier<T> operation) {
        if (!StringUtils.hasText(processInstanceId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Process instance does not exist");
        }
        String key = normalizePrefix(properties.getKeyPrefix()) + ":workflow:process-lock:" + processInstanceId;
        String value = UUID.randomUUID().toString();
        acquire(key, value);
        AtomicBoolean ownershipLost = new AtomicBoolean(false);
        ScheduledFuture<?> renewal = scheduleRenewal(key, value, ownershipLost);
        registerCommitOwnershipCheck(key, value, ownershipLost);
        try {
            T result = operation.get();
            assertOwnership(key, value, ownershipLost);
            return result;
        } finally {
            renewal.cancel(false);
            try {
                redisTemplate.execute(RELEASE_SCRIPT, List.of(key), value);
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to release workflow process lock: key={}", key, exception);
            }
        }
    }

    private ScheduledFuture<?> scheduleRenewal(String key, String value, AtomicBoolean ownershipLost) {
        long ttlMillis = Duration.ofSeconds(properties.getTtlSeconds()).toMillis();
        long intervalMillis = Math.max(RETRY_INTERVAL_MILLIS, ttlMillis / 3);
        return renewalExecutor.scheduleAtFixedRate(() -> {
            try {
                Long result = redisTemplate.execute(
                        RENEW_SCRIPT, List.of(key), value, Long.toString(ttlMillis));
                if (result == null || result == 0L) {
                    ownershipLost.set(true);
                    LOGGER.warn("Workflow process lock ownership was lost before operation completed: key={}", key);
                }
            } catch (RuntimeException exception) {
                ownershipLost.set(true);
                LOGGER.warn("Failed to renew workflow process lock: key={}", key, exception);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void registerCommitOwnershipCheck(String key, String value, AtomicBoolean ownershipLost) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                assertOwnership(key, value, ownershipLost);
            }
        });
    }

    private void assertOwnership(String key, String value, AtomicBoolean ownershipLost) {
        if (ownershipLost.get() || !value.equals(redisTemplate.opsForValue().get(key))) {
            ownershipLost.set(true);
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Process instance lock ownership was lost before commit");
        }
    }

    @PreDestroy
    void shutdownRenewalExecutor() {
        renewalExecutor.shutdownNow();
    }

    private void acquire(String key, String value) {
        long deadline = System.nanoTime() + Duration.ofSeconds(properties.getWaitSeconds()).toNanos();
        Duration ttl = Duration.ofSeconds(properties.getTtlSeconds());
        do {
            if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, value, ttl))) {
                return;
            }
            try {
                Thread.sleep(RETRY_INTERVAL_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.CONFLICT, "Process instance operation was interrupted");
            }
        } while (System.nanoTime() < deadline);
        throw new BusinessException(ErrorCode.CONFLICT, "Another process instance operation is in progress");
    }

    private String normalizePrefix(String prefix) {
        String normalized = StringUtils.hasText(prefix) ? prefix.trim() : "workflow-agent-service";
        while (normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
