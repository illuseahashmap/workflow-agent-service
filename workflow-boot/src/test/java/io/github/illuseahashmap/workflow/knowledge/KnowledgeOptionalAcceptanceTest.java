package io.github.illuseahashmap.workflow.knowledge;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Opt-in acceptance that may use paid model calls or large benchmark corpora. */
@org.junit.jupiter.api.condition.EnabledIf("enabled")
class KnowledgeOptionalAcceptanceTest extends KnowledgeDatabaseFixture {
    static boolean enabled() {
        return Boolean.getBoolean("workflow.rag.benchmark")
                || Boolean.getBoolean("workflow.rag.live-specimen");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
            named = "workflow.rag.benchmark",
            matches = "true")
    void specimenTransferGeneralizationAndScale() throws Exception {
        withScratchDatabase(
                url -> {
                    Flyway.configure()
                            .dataSource(url, databaseUser, databasePassword)
                            .locations(
                                    "classpath:db/migration/platform",
                                    "classpath:db/migration/vector")
                            .load()
                            .migrate();
                    SpecimenTransferBenchmark.run(
                            new DriverManagerDataSource(url, databaseUser, databasePassword));
                });
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
            named = "workflow.rag.live-specimen",
            matches = "true")
    void liveSpecimenAgentUsesOnlyCompleteEvidence() throws Exception {
        withScratchDatabase(
                url -> {
                    Flyway.configure()
                            .dataSource(url, databaseUser, databasePassword)
                            .locations(
                                    "classpath:db/migration/platform",
                                    "classpath:db/migration/vector")
                            .load()
                            .migrate();
                    SpecimenConditionAcceptance.run(
                            new DriverManagerDataSource(url, databaseUser, databasePassword), true);
                });
    }
}
