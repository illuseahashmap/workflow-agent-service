package io.github.illuseahashmap.agent.runtime.infrastructure.execution;

import static org.assertj.core.api.Assertions.assertThat;
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
}
