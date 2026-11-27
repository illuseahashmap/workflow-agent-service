package io.github.illuseahashmap.agent.runtime.application.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.runtime.application.AgentExecutionException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentRouteDecisionParserTest {

    private final AgentRouteDecisionParser parser = new AgentRouteDecisionParser(new ObjectMapper());

    @Test
    void acceptsRagAndMcpOnlyWhenBothCapabilitiesAreAuthorized() {
        String json = """
                {"schemaVersion":"1","mode":"RAG_MCP","reason":"need internal and remote evidence",
                 "requestedTools":["policy_evidence","employee_directory"],"steps":["retrieve","verify"]}
                """;

        AgentRouteDecision decision = parser.parse(
                json, Map.of("policy_evidence", "RAG", "employee_directory", "MCP"));

        assertThat(decision.mode()).isEqualTo(AgentRouteMode.RAG_MCP);
        assertThat(decision.requestedTools()).containsExactly(
                "policy_evidence", "employee_directory");
    }

    @Test
    void rejectsInventedOrModeInconsistentTools() {
        String json = """
                {"schemaVersion":"1","mode":"MCP","reason":"remote lookup",
                 "requestedTools":["mcp:9:hidden"],"steps":["lookup"]}
                """;

        assertThatThrownBy(() -> parser.parse(json, Set.of("mcp:7:directory")))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(exception -> ((AgentExecutionException) exception).failure().errorCode())
                .isEqualTo("AGENT_ROUTE_INVALID");
    }
}
