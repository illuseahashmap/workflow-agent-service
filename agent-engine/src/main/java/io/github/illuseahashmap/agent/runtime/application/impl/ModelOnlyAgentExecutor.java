package io.github.illuseahashmap.agent.runtime.application.impl;

import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;
import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.application.port.AgentCredentialResolver;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.agent.runtime.application.port.AgentExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Current MODEL_ONLY execution strategy: one governed model invocation plus contract validation. */
@Component
public class ModelOnlyAgentExecutor implements AgentExecutor {

    private final AgentCredentialResolver credentialResolver;
    private final ModelProviderRegistry providerRegistry;
    private final AgentOutputSchemaValidator schemaValidator;

    public ModelOnlyAgentExecutor(
            AgentCredentialResolver credentialResolver,
            ModelProviderRegistry providerRegistry,
            AgentOutputSchemaValidator schemaValidator
    ) {
        this.credentialResolver = credentialResolver;
        this.providerRegistry = providerRegistry;
        this.schemaValidator = schemaValidator;
    }

    @Override
    public AgentExecutionMode executionMode() {
        return AgentExecutionMode.MODEL_ONLY;
    }

    @Override
    public Result execute(Command command) {
        var version = command.version();
        var provider = command.provider();
        String model = StringUtils.hasText(version.modelName())
                ? version.modelName() : provider.defaultModel();
        String credential = provider.type() == AgentProviderType.OPENAI_COMPATIBLE
                ? credentialResolver.resolve(command.tenantCode(), provider.id()) : "";
        schemaValidator.validateInput(version.inputSchema(), command.input());
        var response = providerRegistry.require(provider.type()).invoke(new ModelProviderRequest(
                provider.baseUrl(), credential, model, version.systemPrompt(), command.input(),
                command.timeout(), command.traceId()));
        schemaValidator.validateOutput(version.outputSchema(), response.content());
        return new Result(provider.id(), model, response);
    }
}
