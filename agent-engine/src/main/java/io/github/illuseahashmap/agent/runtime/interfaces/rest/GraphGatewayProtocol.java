package io.github.illuseahashmap.agent.runtime.interfaces.rest;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.port.GraphGatewayPort;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException;
import java.util.Map;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.AuthorizationRequest;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.InvokeRequest;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.AUTHORITY_DENIED;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayException.Code.INTERNAL_FAILURE;

/**
 * Transport adapter, deliberately NOT a RestController or Spring bean yet.
 * A production host must enforce TLS, bounded ingress and scoped capability issuance.
 * Keeping it unexposed avoids introducing an alternate cookie/service-token bypass.
 */
public final class GraphGatewayProtocol {
    public static final String PREFIX = "/internal/agent-runtime/v1";
    private final GraphGatewayPort gateway;
    private final GraphGatewayCodec codec;
    public GraphGatewayProtocol(GraphGatewayPort gateway, GraphGatewayCodec codec) {
        this.gateway = gateway;
        this.codec = codec;
    }
    public record Response(int status, byte[] body) { }

    public Response exchange(String method, String path, String authorization, byte[] body) {
        if (!"POST".equals(method)) { return new Response(405, codec.write(Map.of("error_code", "METHOD_NOT_ALLOWED"))); }
        if (!path.equals(PREFIX + "/authorize") && !path.equals(PREFIX + "/invoke")) {
            return new Response(404, codec.write(Map.of("error_code", "NOT_FOUND")));
        }
        try {
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new GraphGatewayException(AUTHORITY_DENIED);
            }
            String capability = authorization.substring(7);
            Object result = path.endsWith("/authorize")
                    ? gateway.authorize(capability, codec.read(body, AuthorizationRequest.class))
                    : gateway.invoke(capability, codec.read(body, InvokeRequest.class));
            return new Response(200, codec.write(result));
        } catch (GraphGatewayException failure) {
            return error(failure.code());
        } catch (RuntimeException failure) {
            return error(INTERNAL_FAILURE);
        }
    }
    private Response error(GraphGatewayException.Code code) {
        return new Response(code.status(), codec.write(Map.of("error_code", code.name())));
    }
}
