package io.github.illuseahashmap.agent.runtime.application.graph;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.AuthorizationRequest;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Authorized;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Deferred;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.InvokeRequest;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Reply;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCallCapacityPort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphGatewayPort;
import io.github.illuseahashmap.agent.runtime.application.port.GraphRunAuthorityPort;
import java.util.List;
import java.util.function.Function;

/** Explicit deployment composition. The resource resolver uses trusted shared account/connector identities. */
public final class GraphCapacityGateway implements GraphGatewayPort {
    private final GraphGatewayPort delegate;
    private final GraphRunAuthorityPort authority;
    private final GraphCallCapacityPort capacity;
    private final Function<InvokeRequest, List<String>> resources;
    private final GraphGatewayCodec codec;

    public GraphCapacityGateway(GraphGatewayPort delegate, GraphRunAuthorityPort authority, GraphCallCapacityPort capacity,
                                Function<InvokeRequest, List<String>> resources, GraphGatewayCodec codec) {
        this.delegate = delegate;
        this.authority = authority;
        this.capacity = capacity;
        this.resources = resources;
        this.codec = codec;
    }
    @Override public Authorized authorize(String capability, AuthorizationRequest request) { return delegate.authorize(capability, request); }
    @Override public Reply invoke(String capability, InvokeRequest request) {
        if ("VALIDATE".equals(request.request().kind())) { return delegate.invoke(capability, request); }
        var run = authority.authorize(capability, request.binding(), request.generation());
        var replay = delegate.replay(capability, request);
        if (replay.isPresent()) {
            capacity.recovered(run, request.request(), replay.get().receipt().receiptRef());
            return replay.get();
        }
        var token = capacity.acquire(run, request.request(), () -> resources.apply(request));
        if (token.isEmpty()) {
            return new Reply(codec.fingerprint(request.binding()), request.request().operationId(), codec.fingerprint(request.request()), null,
                    new Deferred("capacity:" + codec.fingerprint(List.of(request.binding(), request.request())), "QUOTA", System.currentTimeMillis() + 1000));
        }
        try {
            Reply reply = delegate.invoke(capability, request);
            capacity.completed(token.get());
            return reply;
        } catch (RuntimeException | Error failure) {
            // Conservative: neither a local timeout nor a lost lease proves the external request stopped.
            try { capacity.uncertain(token.get()); }
            catch (RuntimeException settlementFailure) { failure.addSuppressed(settlementFailure); }
            throw failure;
        }
    }
}
