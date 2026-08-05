package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.rules.DefaultRuleEngine;
import io.github.illuseahashmap.rules.RuleEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RuleEngineConfig {

    @Bean
    public RuleEngine ruleEngine() {
        return new DefaultRuleEngine();
    }
}
