package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import java.util.List;
import java.util.Optional;

/** Physical in-flight envelope, separate from business idempotency and graph checkpoints. */
public interface GraphCallCapacityPort {
    /** Resolver is database/local only and executes under the policy lock, so rebinding cannot race allocation. */
    Optional<String> acquire(GraphRunAuthorityPort.AuthorizedRun run, Invocation operation, java.util.function.Supplier<List<String>> resources);
    default Optional<String> acquire(GraphRunAuthorityPort.AuthorizedRun run, Invocation operation, List<String> resources) {
        return acquire(run, operation, () -> resources);
    }
    void completed(String token);
    void uncertain(String token);
    void recovered(GraphRunAuthorityPort.AuthorizedRun run, Invocation operation, String receiptReference);
    /** Trusted reconciliation only, with an audited proof reference. Never release by elapsed local TTL. */
    boolean reconcile(String token, String resolutionReference);
}
