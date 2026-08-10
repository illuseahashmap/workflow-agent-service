package io.github.illuseahashmap.agent.provider.application;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderPort;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ModelProviderRegistry {

    private final Map<AgentProviderType, ModelProviderPort> providers;

    public ModelProviderRegistry(List<ModelProviderPort> providers) {
        var indexed = new EnumMap<AgentProviderType, ModelProviderPort>(AgentProviderType.class);
        for (ModelProviderPort provider : providers) {
            ModelProviderPort previous = indexed.put(provider.providerType(), provider);
            if (previous != null) {
                throw new IllegalStateException("Duplicate model Provider adapter: " + provider.providerType());
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public ModelProviderPort require(AgentProviderType providerType) {
        ModelProviderPort provider = providers.get(providerType);
        if (provider == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "No model Provider adapter is registered for " + providerType);
        }
        return provider;
    }
}
