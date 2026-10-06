package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayMigrationConfig {

    @Bean
    @ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
    public Flyway workflowFlyway(@Value("${spring.flyway.url}") String migrationUrl,
                                 @Value("${spring.flyway.username}") String migrationUsername,
                                 @Value("${spring.flyway.password}") String migrationPassword,
                                 @Value("${spring.flyway.baseline-on-migrate:false}") boolean baselineOnMigrate,
                                 @Value("${spring.flyway.baseline-version:0}") String baselineVersion) {
        Flyway flyway = Flyway.configure()
                .dataSource(migrationUrl, migrationUsername, migrationPassword)
                .locations("classpath:db/migration/platform")
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion(baselineVersion)
                .load();
        TrustedDataAccessContext.runAsSystemWorker(flyway::migrate);
        return flyway;
    }
}
