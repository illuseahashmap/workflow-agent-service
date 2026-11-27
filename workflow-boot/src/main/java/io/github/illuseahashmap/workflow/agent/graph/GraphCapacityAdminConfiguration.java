package io.github.illuseahashmap.workflow.agent.graph;

import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCapacityConfiguration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class GraphCapacityAdminConfiguration {
    @Bean
    public io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                    .JdbcGraphCallReconciliation
            graphCallReconciliation(NamedParameterJdbcTemplate jdbc) {
        return new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                .JdbcGraphCallReconciliation(jdbc);
    }

    @Bean
    public JdbcGraphCapacityConfiguration graphCapacityConfiguration(
            NamedParameterJdbcTemplate jdbc) {
        return new JdbcGraphCapacityConfiguration(jdbc);
    }
}
