package io.github.illuseahashmap.workflow.config;

import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlowableEngineConfig {

    @Bean
    public ProcessEngineConfigurationConfigurer processEngineConfigurationConfigurer() {
        return configuration -> configuration.setCreateDiagramOnDeploy(false);
    }
}
