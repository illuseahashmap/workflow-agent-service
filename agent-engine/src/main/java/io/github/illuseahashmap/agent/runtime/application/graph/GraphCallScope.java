package io.github.illuseahashmap.agent.runtime.application.graph;

import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCallLifecyclePort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort.AuthorizedRun;

/** Trusted synchronous call-stack context. Never supplied by wire or inherited by child threads. */
public final class GraphCallScope implements AutoCloseable {
    private static final ThreadLocal<GraphCallScope> CURRENT = new ThreadLocal<>();
    private final GraphCallLifecyclePort lifecycle;
    private final String token;
    private final AuthorizedRun run;
    private final Invocation request;
    private boolean dispatched;
    private boolean returned;

    private GraphCallScope(
            GraphCallLifecyclePort lifecycle, String token, AuthorizedRun run, Invocation request) {
        require(CURRENT.get() == null);
        this.lifecycle = lifecycle;
        this.token = token;
        this.run = run;
        this.request = request;
        CURRENT.set(this);
    }

    public static GraphCallScope open(
            GraphCallLifecyclePort lifecycle, String token, AuthorizedRun run, Invocation request) {
        return new GraphCallScope(lifecycle, token, run, request);
    }

    public static void requireUnbound() {
        require(CURRENT.get() == null);
    }

    private void verify(AuthorizedRun run, Invocation request) {
        require(this.run.equals(run) && this.request.equals(request));
    }

    public static void dispatching(
            AuthorizedRun run, Invocation request, String budgetCallAttempt) {
        var scope = CURRENT.get();
        if (scope == null) {
            return;
        } // Direct gateways have no capacity envelope; deployment uses GraphCapacityGateway.
        scope.verify(run, request);
        require(!scope.dispatched);
        scope.lifecycle.dispatching(scope.token, run, request, budgetCallAttempt);
        scope.dispatched = true;
    }

    public static void returned(AuthorizedRun run, Invocation request) {
        var scope = CURRENT.get();
        if (scope == null) {
            return;
        }
        scope.verify(run, request);
        require(scope.dispatched && !scope.returned);
        scope.lifecycle.returned(scope.token);
        scope.returned = true;
    }

    @Override
    public void close() {
        require(CURRENT.get() == this);
        try {
            lifecycle.finished(token);
        } finally {
            CURRENT.remove();
        }
    }
}
