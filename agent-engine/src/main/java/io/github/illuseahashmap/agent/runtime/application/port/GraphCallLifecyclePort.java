package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Physical envelope facts, independent of model normalization and business acceptance. */
public interface GraphCallLifecyclePort extends GraphCallCapacityPort {
    Optional<String> acquireTracked(AuthorizedRun run, Invocation request, Supplier<List<String>> resources);
    /** Must commit before entering an external adapter. Released permits cannot dispatch. */
    void dispatching(String token, AuthorizedRun run, Invocation request, String budgetCallAttempt);
    /** Trusted synchronous adapter return, allowed after authority loss. Not a business success. */
    void returned(String token);
    /** Local scope has exited: release proven unsent/returned envelopes, quarantine unknown dispatches. */
    void finished(String token);
    /** Recover only matching envelopes with durable proof; never turn a lease timeout into remote completion. */
    void recoverTracked(AuthorizedRun run, Invocation request);
}
