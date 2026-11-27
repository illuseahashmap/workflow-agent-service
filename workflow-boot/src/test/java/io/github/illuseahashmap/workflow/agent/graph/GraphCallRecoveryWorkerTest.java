package io.github.illuseahashmap.workflow.agent.graph;

import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCallCapacityStore;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphCallRecoveryWorkerTest {
    @Test void boundedRecoveryRetriesAfterFailureAndRestoresTrustedContext() {
        var capacity = mock(JdbcGraphCallCapacityStore.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(capacity.quarantineOrphans(100)).thenAnswer(invocation -> {
            assertThat(TrustedDataAccessContext.isSystemWorker()).isTrue();
            if (calls.incrementAndGet() == 1) { throw new IllegalStateException("database unavailable"); }
            return 0;
        });
        var worker = new GraphCallRecoveryWorker(capacity);
        worker.poll();
        assertThat(TrustedDataAccessContext.isSystemWorker()).isFalse();
        worker.poll();
        assertThat(TrustedDataAccessContext.isSystemWorker()).isFalse();
        verify(capacity, times(2)).quarantineOrphans(100);
    }
}
