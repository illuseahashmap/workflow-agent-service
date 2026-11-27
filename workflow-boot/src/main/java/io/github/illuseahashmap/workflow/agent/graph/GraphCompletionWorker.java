package io.github.illuseahashmap.workflow.agent.graph;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphCompletionService;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Single bounded local poller; cross-instance concurrency is fenced by SKIP LOCKED, not JVM locks. */
@Component
@ConditionalOnProperty(name="workflow.agent.graph.enabled", havingValue="true")
public final class GraphCompletionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(GraphCompletionWorker.class);
    private final GraphCompletionService service;
    public GraphCompletionWorker(GraphCompletionService service) { this.service = service; }

    @Scheduled(fixedDelayString="${workflow.agent.graph.completion-interval-ms:1000}", scheduler="graphCompletionScheduler")
    public void poll() {
        TrustedDataAccessContext.runAsSystemWorker(() -> {
            try {
                for (int i = 0; i < 20 && service.completeNext(); i++) { /* bounded drain; no remote IO */ }
            } catch (RuntimeException failure) {
                // Do not log candidate text, credentials or unbounded exception messages.
                LOG.warn("Graph completion interrupted; reconcile durable state on next sweep ({})", failure.getClass().getSimpleName());
            }
            return null;
        });
    }
}
