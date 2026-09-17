package io.github.illuseahashmap.agent.runtime.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;
import io.github.illuseahashmap.agent.runtime.application.port.AgentExecutor;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentExecutorRegistryTest {

    @Test
    void dispatchesOnlyToExplicitlyRegisteredMode() {
        AgentExecutor executor = mock(AgentExecutor.class);
        when(executor.executionMode()).thenReturn(AgentExecutionMode.MODEL_ONLY);
        AgentExecutorRegistry registry = new AgentExecutorRegistry(List.of(executor));

        assertThat(registry.require(AgentExecutionMode.MODEL_ONLY)).isSameAs(executor);
        assertThatThrownBy(() -> registry.require(AgentExecutionMode.REMOTE_AGENT))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not supported");
    }
}
