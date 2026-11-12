package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.AuthorizationRequest;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Authorized;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.InvokeRequest;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Reply;

public interface GraphGatewayPort {
    Authorized authorize(String capability, AuthorizationRequest request);
    Reply invoke(String capability, InvokeRequest request);
}
