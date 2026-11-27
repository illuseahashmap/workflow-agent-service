package io.github.illuseahashmap.agent.runtime.application.routing;

/** Stable capability route selected before the governed execution loop starts. */
public enum AgentRouteMode {
    DIRECT,
    RAG,
    MCP,
    RAG_MCP,
    ABSTAIN
}
