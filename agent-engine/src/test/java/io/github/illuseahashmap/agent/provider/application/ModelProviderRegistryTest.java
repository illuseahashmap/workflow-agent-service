package io.github.illuseahashmap.agent.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderPort;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelProviderRegistryTest {

    @Test
    void resolvesAdapterByProviderType() {
        ModelProviderPort provider = mock(ModelProviderPort.class);
        when(provider.providerType()).thenReturn(AgentProviderType.MOCK);
        ModelProviderRegistry registry = new ModelProviderRegistry(List.of(provider));

        assertThat(registry.require(AgentProviderType.MOCK)).isSameAs(provider);
    }

    @Test
    void rejectsMissingAdapter() {
        ModelProviderRegistry registry = new ModelProviderRegistry(List.of());

        assertThatThrownBy(() -> registry.require(AgentProviderType.OPENAI_COMPATIBLE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No model Provider adapter");
    }
}
