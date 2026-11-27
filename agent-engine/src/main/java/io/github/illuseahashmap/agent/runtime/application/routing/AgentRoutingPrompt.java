package io.github.illuseahashmap.agent.runtime.application.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest.ToolDefinition;

import java.util.List;

/** One phase contract for native and graph routing; tool authorization stays in the parser. */
public final class AgentRoutingPrompt {
    private AgentRoutingPrompt() {}

    public static String render(
            String businessInstructions, List<ToolDefinition> tools, ObjectMapper json) {
        try {
            return """
            You are the routing phase of a governed workflow Agent. Do not answer the request.
            Choose a route using only the authorized tool capabilities supplied below.
            Select exactly one mode from DIRECT, RAG, MCP, RAG_MCP, ABSTAIN.
            requestedTools must contain exact name values from the authorized tool list, never descriptions or aliases.
            DIRECT may use platform-native tools, but not RAG or MCP tools. ABSTAIN requests no tools.
            RAG requires a RAG tool; MCP requires an MCP tool; RAG_MCP requires both.
            No retrieval or tool call has happened yet. Missing observations at this stage are normal:
            select available tools that can obtain the required evidence before judging answerability.
            ABSTAIN only when no authorized route can help, not merely because evidence has not been fetched.
            The business instructions below describe the eventual task. Their answer format and
            final evidence requirements apply after tool execution, not to this routing response.
            Business instructions (JSON string):
            """
                    + json.writeValueAsString(businessInstructions)
                    + "\nAvailable tools (exact name, description, input schema, capability):\n"
                    + json.writeValueAsString(tools)
                    + "\n"
                    + "Return only one JSON object matching this routing schema, with at most five"
                    + " concise steps:\n"
                    + AgentRouteDecisionParser.JSON_SCHEMA;
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Cannot serialize routing context", invalid);
        }
    }
}
