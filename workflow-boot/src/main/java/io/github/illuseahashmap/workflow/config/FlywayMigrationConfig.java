package io.github.illuseahashmap.workflow.config;

import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.List;

@Configuration
public class FlywayMigrationConfig {

    @Bean
    @ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
    public Flyway workflowFlyway(@Value("${spring.flyway.url}") String migrationUrl,
                                 @Value("${spring.flyway.username}") String migrationUsername,
                                 @Value("${spring.flyway.password}") String migrationPassword,
                                 @Value("${spring.flyway.baseline-on-migrate:false}") boolean baselineOnMigrate,
                                 @Value("${spring.flyway.baseline-version:0}") String baselineVersion,
                                 Environment environment) {
        List<String> locations = migrationLocations(environment);
        var configuration = Flyway.configure()
                .dataSource(migrationUrl, migrationUsername, migrationPassword)
                .locations(locations.toArray(String[]::new))
                .baselineOnMigrate(baselineOnMigrate)
                .baselineVersion(baselineVersion);
        boolean vectorLocationEnabled = locations.contains("classpath:db/migration/vector");
        if (!vectorLocationEnabled) {
            // A database that previously ran the opt-in vector profile must still boot in keyword-only mode.
            // Restrict the exception to its one known repeatable migration below; never ignore missing versions.
            configuration.ignoreMigrationPatterns("*:future", "repeatable:missing");
        }
        Flyway flyway = configuration.load();
        if (!vectorLocationEnabled) {
            rejectUnknownMissingMigrations(flyway.info().all());
        }
        TrustedDataAccessContext.runAsSystemWorker(flyway::migrate);
        return flyway;
    }

    static List<String> migrationLocations(Environment environment) {
        return Binder.get(environment)
                .bind("spring.flyway.locations", Bindable.listOf(String.class))
                .orElse(List.of("classpath:db/migration/platform"));
    }

    static void rejectUnknownMissingMigrations(MigrationInfo[] migrations) {
        for (MigrationInfo migration : migrations) {
            if (migration.getState() == MigrationState.MISSING_SUCCESS
                    && !"knowledge chunk embeddings".equals(migration.getDescription())) {
                throw new IllegalStateException("Unexpected missing repeatable migration: "
                        + migration.getDescription());
            }
        }
    }
}
