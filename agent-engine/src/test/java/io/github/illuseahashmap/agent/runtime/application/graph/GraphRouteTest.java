package io.github.illuseahashmap.agent.runtime.application.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.agent.runtime.application.AgentExecutionException;
import io.github.illuseahashmap.agent.runtime.application.routing.AgentRouteMode;

import org.junit.jupiter.api.Test;

import java.util.Map;

class GraphRouteTest {
    private static final String DIRECT =
            "{\"schemaVersion\":\"1\",\"mode\":\"DIRECT\",\"reason\":\"answer\",\"requestedTools\":[],\"steps\":[]}";

    @Test
    void nativeContractAndStoredCandidateAgree() {
        assertThat(GraphRoute.parse(DIRECT, Map.of()).mode()).isEqualTo(AgentRouteMode.DIRECT);
        assertThat(GraphRoute.decoded(DIRECT)).isEqualTo(GraphRoute.parse(DIRECT, Map.of()));
    }

    @Test
    void ambiguousOrNullCandidateCannotBecomeRoute() {
        assertThatThrownBy(() -> GraphRoute.parse(DIRECT + " {}", Map.of()))
                .isInstanceOf(AgentExecutionException.class);
        assertThatThrownBy(() -> GraphRoute.parse("null", Map.of()))
                .isInstanceOf(AgentExecutionException.class);
        assertThatThrownBy(() -> GraphRoute.decoded("{broken"))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void futurePolicyRequiresExplicitProtocolSupport() {
        assertThatThrownBy(() -> new GraphRoute.Policy(2, true))
                .isInstanceOf(GraphGatewayException.class);
    }
}
