package io.github.illuseahashmap.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedVirtualThreadTaskExecutorTest {

    @Test
    void rejectsBeyondLimitAndReleasesPermitAfterCompletion() throws InterruptedException {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = new BoundedVirtualThreadTaskExecutor(1, "test-vt-", Duration.ofSeconds(2))) {
            assertThat(executor.tryExecute(() -> {
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            })).isTrue();
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.availablePermits()).isZero();
            assertThat(executor.tryExecute(() -> { })).isFalse();

            release.countDown();
            assertThat(waitForPermit(executor)).isTrue();
        }
    }

    private boolean waitForPermit(BoundedVirtualThreadTaskExecutor executor) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (executor.availablePermits() == 1) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }
}
