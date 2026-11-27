package io.github.illuseahashmap.agent.runtime.infrastructure.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.agent.runtime.application.port.AgentRunExecutionRepository;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunLeaseHeartbeat;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRuntimeMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ScheduledAgentRunLeaseHeartbeatTest {

    @Test
    void renewsOwnedAttemptOnDedicatedScheduler() throws Exception {
        AgentRunExecutionRepository repository = mock(AgentRunExecutionRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(repository.renewLease(eq("tenant-a"), eq(10L), eq(20L), eq("worker-a"), any(), any()))
                .thenReturn(true);
        ScheduledAgentRunLeaseHeartbeat heartbeat = new ScheduledAgentRunLeaseHeartbeat(
                repository, new TransactionTemplate(transactionManager), AgentRuntimeMetrics.NOOP);

        try (AgentRunLeaseHeartbeat.LeaseHandle handle = heartbeat.start(
                new AgentRunLeaseHeartbeat.LeaseCommand(
                        "tenant-a", 10L, 20L, "worker-a", Duration.ofSeconds(3),
                        Instant.now().plusSeconds(30)))) {
            Thread.sleep(1_200L);
            assertThat(handle.isValid()).isTrue();
            verify(repository).renewLease(eq("tenant-a"), eq(10L), eq(20L), eq("worker-a"), any(), any());
        } finally {
            heartbeat.close();
        }
    }

    @Test
    void oneBlockedRenewalDoesNotStarveAnotherRun() throws Exception {
        AgentRunExecutionRepository repository = mock(AgentRunExecutionRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondRenewed = new CountDownLatch(1);
        when(repository.renewLease(eq("tenant-a"), eq(10L), eq(20L), eq("worker-a"), any(), any()))
                .thenAnswer(invocation -> {
                    firstStarted.countDown();
                    releaseFirst.await(3, TimeUnit.SECONDS);
                    return true;
                });
        when(repository.renewLease(eq("tenant-a"), eq(11L), eq(21L), eq("worker-a"), any(), any()))
                .thenAnswer(invocation -> {
                    secondRenewed.countDown();
                    return true;
                });
        ScheduledAgentRunLeaseHeartbeat heartbeat = new ScheduledAgentRunLeaseHeartbeat(
                repository, new TransactionTemplate(transactionManager), AgentRuntimeMetrics.NOOP, 2);

        AgentRunLeaseHeartbeat.LeaseCommand first = new AgentRunLeaseHeartbeat.LeaseCommand(
                "tenant-a", 10L, 20L, "worker-a", Duration.ofSeconds(3), Instant.now().plusSeconds(30));
        AgentRunLeaseHeartbeat.LeaseCommand second = new AgentRunLeaseHeartbeat.LeaseCommand(
                "tenant-a", 11L, 21L, "worker-a", Duration.ofSeconds(3), Instant.now().plusSeconds(30));
        try (AgentRunLeaseHeartbeat.LeaseHandle ignoredFirst = heartbeat.start(first);
             AgentRunLeaseHeartbeat.LeaseHandle ignoredSecond = heartbeat.start(second)) {
            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(secondRenewed.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseFirst.countDown();
            heartbeat.close();
        }
    }

    @Test
    void boundsTrackedRunsAndReleasesCapacityWhenHandleCloses() {
        AgentRunExecutionRepository repository = mock(AgentRunExecutionRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        ScheduledAgentRunLeaseHeartbeat heartbeat = new ScheduledAgentRunLeaseHeartbeat(
                repository, new TransactionTemplate(transactionManager), AgentRuntimeMetrics.NOOP, 1, 1);
        AgentRunLeaseHeartbeat.LeaseCommand command = new AgentRunLeaseHeartbeat.LeaseCommand(
                "tenant-a", 10L, 20L, "worker-a", Duration.ofSeconds(30),
                Instant.now().plusSeconds(120));

        AgentRunLeaseHeartbeat.LeaseHandle first = heartbeat.start(command);
        try {
            assertThatThrownBy(() -> heartbeat.start(command))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("capacity exhausted");
            first.close();
            try (AgentRunLeaseHeartbeat.LeaseHandle second = heartbeat.start(command)) {
                assertThat(second.isValid()).isTrue();
            }
        } finally {
            first.close();
            heartbeat.close();
        }
    }
}
