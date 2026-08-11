package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import java.time.Duration;

/** Executes one immutable Agent version without owning scheduling or ledger transitions. */
public interface AgentExecutor {

    AgentExecutionMode executionMode();

    Result execute(Command command);

    record Command(
            String tenantCode,
            AgentDefinitionVersion version,
            AgentProvider provider,
            String input,
            Duration timeout,
            String traceId
    ) {
    }

    record Result(long providerId, String requestedModel, ModelProviderResponse modelResponse) {
    }
}
