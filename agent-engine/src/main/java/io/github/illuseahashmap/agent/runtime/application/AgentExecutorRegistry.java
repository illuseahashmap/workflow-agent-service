package io.github.illuseahashmap.agent.runtime.application;

import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;
import io.github.illuseahashmap.agent.runtime.application.port.AgentExecutor;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Deterministic dispatch registry; unknown modes never fall back to MODEL_ONLY. */
@Component
public class AgentExecutorRegistry {

    private final Map<AgentExecutionMode, AgentExecutor> executors;

    public AgentExecutorRegistry(List<AgentExecutor> executors) {
        var registered = new EnumMap<AgentExecutionMode, AgentExecutor>(AgentExecutionMode.class);
        for (AgentExecutor executor : executors) {
            if (registered.put(executor.executionMode(), executor) != null) {
                throw new IllegalStateException("Duplicate Agent executor for " + executor.executionMode());
            }
        }
        this.executors = Map.copyOf(registered);
    }

    public AgentExecutor require(AgentExecutionMode mode) {
        AgentExecutor executor = executors.get(mode);
        if (executor == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "Agent execution mode is not supported: " + mode);
        }
        return executor;
    }
}
