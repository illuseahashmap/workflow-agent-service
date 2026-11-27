package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.AuthorizationRequest;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Authorized;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.InvokeRequest;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Reply;

public interface GraphGatewayPort {
    /** True only when every external adapter boundary uses GraphCallScope. */
    default boolean tracksPhysicalCalls() { return false; }
    /** Read-only context validation before any capacity claim or logical admission. */
    default void preflight(String capability, InvokeRequest request) { }
    Authorized authorize(String capability, AuthorizationRequest request);
    Reply invoke(String capability, InvokeRequest request);
    /** No external I/O. Revalidates current access before reusing immutable stored results. */
    default java.util.Optional<Reply> replay(String capability, InvokeRequest request) { return java.util.Optional.empty(); }
}
