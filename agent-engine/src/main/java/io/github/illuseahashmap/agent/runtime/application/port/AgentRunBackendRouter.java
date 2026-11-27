package io.github.illuseahashmap.agent.runtime.application.port;

/** Called inside the business submission transaction. Existing versions default to native. */
public interface AgentRunBackendRouter {
    AgentRunBackendRouter NATIVE = (tenant, version, run) -> {};

    default void beforeSubmit(String tenant, long version) {}

    void route(String tenant, long version, long run);
}
