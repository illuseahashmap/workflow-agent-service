package io.github.illuseahashmap.agent.provider.application.port;

import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;

public interface ModelProviderPort {

    AgentProviderType providerType();

    ModelProviderResponse invoke(ModelProviderRequest request);
}
