package io.github.illuseahashmap.workflow.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.workflow.shared.event.IntegrationEventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JdbcReliableEventPublisherTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void notifiesOnlyAfterTransactionCommit() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        OutboxCommitNotifier notifier = mock(OutboxCommitNotifier.class);
        when(jdbcTemplate.update(any(String.class), any(java.util.Map.class))).thenReturn(1);
        var publisher = new JdbcReliableEventPublisher(jdbcTemplate, List.of(notifier));
        TransactionSynchronizationManager.initSynchronization();

        publisher.publish(event("AgentRunCompleted.v1"));

        verify(notifier, never()).committed(any());
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
        verify(notifier).committed("AgentRunCompleted.v1");
    }

    @Test
    void duplicateEventDoesNotWakeDispatcher() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        OutboxCommitNotifier notifier = mock(OutboxCommitNotifier.class);
        when(jdbcTemplate.update(any(String.class), any(java.util.Map.class))).thenReturn(0);
        var publisher = new JdbcReliableEventPublisher(jdbcTemplate, List.of(notifier));

        publisher.publish(event("AgentRunCompleted.v1"));

        verify(notifier, never()).committed(any());
    }

    private IntegrationEventEnvelope event(String type) {
        return new IntegrationEventEnvelope(
                UUID.randomUUID(), type, "AgentRun", "1", "tenant-a", "trace-a", "{}", Instant.now());
    }
}
