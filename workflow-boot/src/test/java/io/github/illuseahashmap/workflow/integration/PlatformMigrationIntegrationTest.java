package io.github.illuseahashmap.workflow.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PlatformMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeAll
    static void migrateDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/platform")
                .load()
                .migrate();
    }

    @Test
    void migratesCleanDatabaseThroughLatestPlatformVersion() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM flyway_schema_history
                     WHERE success = TRUE AND version IS NOT NULL
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(13);
        }
        assertThat(tableExists("workflow_node_assignment_rule")).isTrue();
        assertThat(tableExists("auth_user_tenant")).isTrue();
        assertThat(tableExists("workflow_assignment_fallback_command")).isTrue();
        assertThat(tableExists("platform_security_audit")).isTrue();
        assertThat(tableExists("auth_attempt_guard")).isTrue();
    }

    @Test
    void rejectsCrossTenantAssignmentRuleChildren() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_tenant (tenant_id, tenant_code, tenant_name)
                    VALUES ('tenant-a', 'tenant-a', 'Tenant A'), ('tenant-b', 'tenant-b', 'Tenant B')
                    ON CONFLICT (tenant_code) DO NOTHING
                    """);
            ResultSet resultSet = statement.executeQuery("""
                    INSERT INTO workflow_node_assignment_rule
                        (tenant_id, process_definition_id, process_definition_key, version,
                         task_definition_key, assignment_type, empty_user_strategy)
                    VALUES ('tenant-a', 'definition-a:1', 'definition-a', 1,
                            'approve', 'ASSIGNEE', 'ERROR')
                    RETURNING id
                    """);
            assertThat(resultSet.next()).isTrue();
            long ruleId = resultSet.getLong(1);

            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO workflow_assignment_target
                        (tenant_id, rule_id, target_type, target_value, sort_order)
                    VALUES ('tenant-b', %d, 'ASSIGNEE', 'user-a', 10)
                    """.formatted(ruleId)))
                    .isInstanceOf(PSQLException.class);
        }
    }

    @Test
    void fallbackCommandAllowsOnlyOneTerminalActionPerTask() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_assignment_fallback_command
                        (tenant_id, task_id, process_instance_id, action, status)
                    VALUES ('tenant-a', 'task-unique', 'process-1', 'AUTO_COMPLETE', 'PENDING')
                    """);

            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO workflow_assignment_fallback_command
                        (tenant_id, task_id, process_instance_id, action, status)
                    VALUES ('tenant-a', 'task-unique', 'process-1', 'AUTO_REJECT', 'PENDING')
                    """))
                    .isInstanceOf(PSQLException.class);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private boolean tableExists(String tableName) throws SQLException {
        try (Connection connection = connection(); ResultSet tables = connection.getMetaData()
                .getTables(null, "public", tableName, new String[]{"TABLE"})) {
            return tables.next();
        }
    }
}
