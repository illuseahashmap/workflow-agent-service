package io.github.illuseahashmap.agent.runtime.application.graph;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.github.illuseahashmap.agent.runtime.application.routing.AgentRouteDecision;
import io.github.illuseahashmap.agent.runtime.application.routing.AgentRouteDecisionParser;

import java.util.Map;

/** Frozen routing policy; the proposal never grants tool authority. */
public final class GraphRoute {
    private static final JsonMapper JSON =
            JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private GraphRoute() {}

    public static AgentRouteDecision parse(String content, Map<String, String> capabilities) {
        return new AgentRouteDecisionParser(JSON).parse(content, capabilities);
    }

    public static AgentRouteDecision decoded(String content) {
        try {
            return JSON.readValue(content, AgentRouteDecision.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new GraphGatewayException(GraphGatewayException.Code.INVALID_REQUEST);
        }
    }

    public record Policy(int version, boolean allowCorrection) {
        public Policy {
            GraphGatewayContract.require(version == 1);
        }
    }

    public record Context(Policy policy, AgentRouteDecision accepted, String repairOriginal) {}
}
