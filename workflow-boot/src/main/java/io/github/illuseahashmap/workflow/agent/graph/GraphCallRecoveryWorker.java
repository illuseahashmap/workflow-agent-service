package io.github.illuseahashmap.workflow.agent.graph;

import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCallCapacityStore;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded proof-based recovery, including stopped Runs with no future worker requests. */
public final class GraphCallRecoveryWorker {
    private static final Logger LOG=LoggerFactory.getLogger(GraphCallRecoveryWorker.class);
    private final JdbcGraphCallCapacityStore capacity;
    public GraphCallRecoveryWorker(JdbcGraphCallCapacityStore capacity) { this.capacity=capacity; }
    @Scheduled(fixedDelayString="${workflow.agent.graph.call-recovery-interval-ms:1000}", scheduler="graphCompletionScheduler")
    public void poll() {
        TrustedDataAccessContext.runAsSystemWorker(() -> {
            try { capacity.quarantineOrphans(100); }
            catch (RuntimeException failure) { LOG.warn("Graph call recovery deferred ({})",failure.getClass().getSimpleName()); }
            return null;
        });
    }
}
