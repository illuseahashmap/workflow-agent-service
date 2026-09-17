package io.github.illuseahashmap.agent.provider.infrastructure.mock;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderPort;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MockModelProviderAdapter implements ModelProviderPort {

    @Override
    public AgentProviderType providerType() {
        return AgentProviderType.MOCK;
    }

    @Override
    public ModelProviderResponse invoke(ModelProviderRequest request) {
        String content = """
                {"decision":"APPROVE","summary":"Mock result","confidence":0.9}
                """.trim();
        return new ModelProviderResponse(
                content,
                request.model() == null ? "mock-model" : request.model(),
                "mock-" + UUID.randomUUID(),
                "stop",
                0,
                0,
                0,
                0);
    }
}
