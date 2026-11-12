package io.github.illuseahashmap.workflow.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.application.port.AgentCredentialResolver;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderRepository;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry;
import io.github.illuseahashmap.agent.runtime.application.checkpoint.CheckpointContextPolicy;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphCapacityGateway;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphCognitionGateway;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphToolGateway;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCallCapacityStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCognitionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphExecutionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphReferenceStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration;
import io.github.illuseahashmap.agent.runtime.interfaces.rest.GraphGatewayProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name="workflow.agent.graph.enabled", havingValue="true")
public class GraphHostConfiguration {
    @Bean public GraphHostSettings graphHostSettings(
            @Value("${workflow.agent.graph.worker-token}") String token,
            @Value("${workflow.agent.graph.artifact-digest}") String artifact,
            @Value("${workflow.agent.graph.lease-seconds:90}") int lease,
            @Value("${workflow.agent.graph.allow-loopback-http:false}") boolean loopback) {
        return new GraphHostSettings(token, artifact, lease, loopback);
    }
    @Bean public JdbcGraphExecutionStore graphExecutions(NamedParameterJdbcTemplate jdbc) {
        return new JdbcGraphExecutionStore(jdbc, new GraphGatewayCodec());
    }
    @Bean public JdbcGraphResourceConfiguration graphResources(NamedParameterJdbcTemplate jdbc) {
        return new JdbcGraphResourceConfiguration(jdbc);
    }
    @Bean public GraphGatewayProtocol graphProtocol(NamedParameterJdbcTemplate jdbc, JdbcGraphExecutionStore executions,
            JdbcGraphResourceConfiguration resources, AgentToolRegistry registry, AgentDefinitionVersionRepository versions,
            AgentProviderRepository providers, AgentCredentialResolver credentials, ModelProviderRegistry models,
            AgentOutputSchemaValidator schemas, CheckpointContextPolicy budget, ObjectMapper mapper) {
        var codec = new GraphGatewayCodec();
        var tools = new GraphToolGateway(executions, new JdbcGraphReferenceStore(jdbc, executions, codec), registry, codec);
        var cognition = new GraphCognitionGateway(tools, executions, new JdbcGraphCognitionStore(jdbc, executions, codec),
                versions, providers, credentials, models, registry, schemas, budget, mapper, codec);
        return new GraphGatewayProtocol(new GraphCapacityGateway(cognition, executions,
                new JdbcGraphCallCapacityStore(jdbc, executions, codec), resources, codec), codec);
    }
    public record GraphHostSettings(String workerToken, String artifactDigest, int leaseSeconds, boolean allowLoopbackHttp) {
        public GraphHostSettings {
            require(workerToken != null && workerToken.matches("[A-Za-z0-9_.:-]{32,256}"));
            GraphGatewayContract.digest(artifactDigest);
            require(leaseSeconds >= 30 && leaseSeconds <= 120);
        }
        @Override public String toString() { return "GraphHostSettings[token=REDACTED]"; }
    }
}
