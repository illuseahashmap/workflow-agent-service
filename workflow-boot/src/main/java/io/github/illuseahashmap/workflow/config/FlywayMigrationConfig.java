package io.github.illuseahashmap.workflow.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayMigrationConfig {

    @Bean(initMethod = "migrate")
    @ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
    public Flyway workflowFlyway(DataSource dataSource,
                                 @Value("${spring.flyway.baseline-on-migrate:false}") boolean baselineOnMigrate,
                                 @Value("${spring.flyway.baseline-version:0}") String baselineVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/workflow", "classpath:db/migration/auth")
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion(baselineVersion)
                .load();
    }
}
