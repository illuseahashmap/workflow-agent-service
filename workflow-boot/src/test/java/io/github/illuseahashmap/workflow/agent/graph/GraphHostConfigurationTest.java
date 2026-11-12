package io.github.illuseahashmap.workflow.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.application.port.AgentCredentialResolver;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderRepository;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry;
import io.github.illuseahashmap.agent.runtime.application.checkpoint.CheckpointContextPolicy;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphExecutionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration;
import io.github.illuseahashmap.agent.runtime.interfaces.rest.GraphGatewayProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GraphHostConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GraphHostConfiguration.class)
            .withBean(NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://unused/test")))
            .withBean(AgentToolRegistry.class, () -> mock(AgentToolRegistry.class))
            .withBean(AgentDefinitionVersionRepository.class, () -> mock(AgentDefinitionVersionRepository.class))
            .withBean(AgentProviderRepository.class, () -> mock(AgentProviderRepository.class))
            .withBean(AgentCredentialResolver.class, () -> mock(AgentCredentialResolver.class))
            .withBean(ModelProviderRegistry.class, () -> mock(ModelProviderRegistry.class))
            .withBean(AgentOutputSchemaValidator.class, () -> mock(AgentOutputSchemaValidator.class))
            .withBean(CheckpointContextPolicy.class, () -> mock(CheckpointContextPolicy.class))
            .withBean(io.github.illuseahashmap.agent.runtime.application.port.AgentRunEventPublisher.class,
                    () -> mock(io.github.illuseahashmap.agent.runtime.application.port.AgentRunEventPublisher.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test void disabledHostDoesNotRequireSecretsOrCreateExecutionBeans() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(GraphGatewayProtocol.class));
    }
    @Test void enabledHostComposesRealAdaptersWithoutConnectingAtStartup() {
        runner.withPropertyValues("workflow.agent.graph.enabled=true", "workflow.agent.graph.worker-token=" + "x".repeat(32),
                "workflow.agent.graph.artifact-digest=" + "a".repeat(64)).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(GraphGatewayProtocol.class)
                            .hasSingleBean(JdbcGraphExecutionStore.class).hasSingleBean(JdbcGraphResourceConfiguration.class);
                    assertThat(context.getBean(GraphHostConfiguration.GraphHostSettings.class).toString()).doesNotContain("x".repeat(32));
                });
    }
    @Test void enabledHostFailsClosedWithInvalidWorkerConfiguration() {
        runner.withPropertyValues("workflow.agent.graph.enabled=true", "workflow.agent.graph.worker-token=short",
                "workflow.agent.graph.artifact-digest=" + "a".repeat(64)).run(context -> assertThat(context).hasFailed());
    }
}
