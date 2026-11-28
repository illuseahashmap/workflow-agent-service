package io.github.illuseahashmap.workflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

class BranchMigrationUpgradeTest extends KnowledgeDatabaseFixture {
    @TempDir Path legacy;

    private Flyway flyway(String url, String location, String target, boolean outOfOrder) {
        return Flyway.configure()
                .dataSource(url, databaseUser, databasePassword)
                .locations(location)
                .target(target)
                .outOfOrder(outOfOrder)
                .load();
    }

    @Test
    void releasedIntegrationAndFreshDatabasesUpgradeWithoutHistoryChanges() throws Exception {
        for (String target : new String[] {"78", "80", "83"}) {
            withScratchDatabase(
                    url -> {
                        var location = "classpath:db/migration/platform";
                        flyway(url, location, target, false).migrate();
                        var jdbc =
                                new JdbcTemplate(
                                        new DriverManagerDataSource(
                                                url, databaseUser, databasePassword));
                        var before =
                                jdbc.queryForList(
                                        "SELECT * FROM flyway_schema_history ORDER BY"
                                                + " installed_rank");
                        flyway(url, location, "latest", false).migrate();
                        flyway(url, location, "latest", false).validate();
                        assertThat(
                                        jdbc.queryForList(
                                                "SELECT * FROM flyway_schema_history ORDER BY"
                                                        + " installed_rank"))
                                .startsWith(before.toArray(new java.util.Map[0]));
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT count(*) FROM flyway_schema_history WHERE"
                                                    + " version IN ('81','82','83','84','85') AND"
                                                    + " success",
                                                Integer.class))
                                .isEqualTo(5);
                    });
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"81", "82"})
    void exactMainHistoryIsAuditedRemappedAndValidatedWithoutRepair(String oldTarget)
            throws Exception {
        var source = Path.of("../platform-migrations/src/main/resources/db/migration/platform");
        try (var paths = Files.list(source)) {
            for (var file : paths.toList()) {
                var name = file.getFileName().toString();
                if (name.startsWith("V81__")
                        || name.startsWith("V82__")
                        || name.startsWith("V83__")) {
                    continue;
                }
                name = name.replace("V84__", "V81__").replace("V85__", "V82__");
                Files.copy(file, legacy.resolve(name));
            }
        }
        withScratchDatabase(
                url -> {
                    flyway(url, "filesystem:" + legacy, oldTarget, false).migrate();
                    var jdbc =
                            new JdbcTemplate(
                                    new DriverManagerDataSource(
                                            url, databaseUser, databasePassword));
                    var bridge =
                            Files.readString(Path.of("../scripts/migrations/bridge-main-v82.sql"))
                                    .replace("\uFEFF", "");
                    jdbc.update("UPDATE flyway_schema_history SET checksum=0 WHERE version='81'");
                    var untrusted =
                            jdbc.queryForList(
                                    "SELECT * FROM flyway_schema_history ORDER BY installed_rank");
                    assertThatThrownBy(() -> jdbc.execute(bridge))
                            .hasMessageContaining("Not the exact supported");
                    assertThat(
                                    jdbc.queryForList(
                                            "SELECT * FROM flyway_schema_history ORDER BY"
                                                + " installed_rank"))
                            .isEqualTo(untrusted);
                    jdbc.update(
                            "UPDATE flyway_schema_history SET checksum=-778765555 WHERE"
                                + " version='81'");
                    jdbc.execute(bridge);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM workflow_migration_bridge_audit",
                                            Integer.class))
                            .isEqualTo(1);
                    flyway(url, "classpath:db/migration/platform", "latest", true).migrate();
                    flyway(url, "classpath:db/migration/platform", "latest", false).validate();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM flyway_schema_history WHERE"
                                                    + " version IN ('81','82','83','84','85') AND"
                                                    + " success",
                                            Integer.class))
                            .isEqualTo(5);
                    assertThatThrownBy(() -> jdbc.execute(bridge))
                            .hasMessageContaining("Not the exact supported");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM workflow_migration_bridge_audit",
                                            Integer.class))
                            .isEqualTo(1);
                });
    }

    @Test
    void unknownHistoryIsRejectedWithoutModification() throws Exception {
        withScratchDatabase(
                url -> {
                    flyway(url, "classpath:db/migration/platform", "83", false).migrate();
                    var jdbc =
                            new JdbcTemplate(
                                    new DriverManagerDataSource(
                                            url, databaseUser, databasePassword));
                    var before =
                            jdbc.queryForList(
                                    "SELECT * FROM flyway_schema_history ORDER BY installed_rank");
                    assertThatThrownBy(
                                    () ->
                                            jdbc.execute(
                                                    Files.readString(
                                                                    Path.of(
                                                                            "../scripts/migrations/bridge-main-v82.sql"))
                                                            .replace("\uFEFF", "")))
                            .hasMessageContaining("Not the exact supported");
                    assertThat(
                                    jdbc.queryForList(
                                            "SELECT * FROM flyway_schema_history ORDER BY"
                                                    + " installed_rank"))
                            .isEqualTo(before);
                });
    }
}
