package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;

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
            String traceId,
            String processInstanceId,
            String nodeToolSetJson,
            BiConsumer<Integer, StepResult> progressListener
    ) {
        public Command(String tenantCode, AgentDefinitionVersion version, AgentProvider provider,
                       String input, Duration timeout, String traceId) {
            this(tenantCode, version, provider, input, timeout, traceId, null, null, null);
        }

        public Command(
                String tenantCode, AgentDefinitionVersion version, AgentProvider provider,
                String input, Duration timeout, String traceId, String processInstanceId) {
            this(tenantCode, version, provider, input, timeout, traceId, processInstanceId, null, null);
        }

        public Command(
                String tenantCode, AgentDefinitionVersion version, AgentProvider provider,
                String input, Duration timeout, String traceId, String processInstanceId,
                String nodeToolSetJson) {
            this(tenantCode, version, provider, input, timeout, traceId, processInstanceId,
                    nodeToolSetJson, null);
        }
    }

    record Result(long providerId, String requestedModel, ModelProviderResponse modelResponse,
                  List<StepResult> steps, boolean progressPersisted) {
        public Result {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        public Result(long providerId, String requestedModel, ModelProviderResponse modelResponse) {
            this(providerId, requestedModel, modelResponse, List.of(), false);
        }

        public Result(long providerId, String requestedModel, ModelProviderResponse modelResponse,
                      List<StepResult> steps) {
            this(providerId, requestedModel, modelResponse, steps, false);
        }
    }

    record StepResult(String stepType, String status, String errorCode) {
    }
}
