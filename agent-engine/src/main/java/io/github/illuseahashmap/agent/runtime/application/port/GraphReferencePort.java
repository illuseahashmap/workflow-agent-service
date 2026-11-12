package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;

/** Trusted, immutable reference storage. Never fetch a URL supplied by the runtime. */
public interface GraphReferencePort {
    /**
     * Look up an approved MODEL tool intent; authorize tenant/run/version/op/tool.
     * A reference minted by another run or operation MUST fail, even in the same tenant.
     */
    Arguments arguments(GraphRunAuthorityPort.AuthorizedRun run, Invocation request);

    /**
     * Atomically insert-or-compare a receipt reference to the existing tool ledger.
     * Replays return the same reference; conflicting identity/content must fail.
     * This does not re-execute tools and does not own the graph's next step.
     */
    String recordReceipt(GraphRunAuthorityPort.AuthorizedRun run, Invocation request,
                         String idempotencyKey, String outputDigest);

    record Arguments(String bindingDigest, String operationId, String toolCode,
                     String argumentsDigest, String json) { }
}
