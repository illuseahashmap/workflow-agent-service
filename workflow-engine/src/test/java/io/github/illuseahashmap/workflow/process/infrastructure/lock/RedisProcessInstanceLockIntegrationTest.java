package io.github.illuseahashmap.workflow.process.infrastructure.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RedisProcessInstanceLockIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static RedisProcessInstanceLock firstLock;
    private static RedisProcessInstanceLock secondLock;

    @BeforeAll
    static void setUpRedis() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        WorkflowLockProperties properties = new WorkflowLockProperties();
        properties.setKeyPrefix("workflow-agent-test");
        properties.setWaitSeconds(1);
        properties.setTtlSeconds(3);
        firstLock = new RedisProcessInstanceLock(redisTemplate, properties);
        secondLock = new RedisProcessInstanceLock(redisTemplate, properties);
    }

    @AfterAll
    static void tearDownRedis() {
        if (firstLock != null) {
            firstLock.shutdownRenewalExecutor();
        }
        if (secondLock != null) {
            secondLock.shutdownRenewalExecutor();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void serializesCommandsAcrossIndependentLockInstances() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<String> firstOperation = executor.submit(() -> firstLock.execute("process-1", () -> {
                acquired.countDown();
                await(release);
                return "first";
            }));
            assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> secondLock.execute("process-1", () -> "second"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT));

            release.countDown();
            assertThat(firstOperation.get(2, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(secondLock.execute("process-1", () -> "after-release")).isEqualTo("after-release");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while testing process lock", exception);
        }
    }
}
