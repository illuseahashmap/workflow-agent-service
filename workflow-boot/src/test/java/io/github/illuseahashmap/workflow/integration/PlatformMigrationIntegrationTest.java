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
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            // The platform migration must also protect Flowable tables that already exist
            // when it runs. This representative table makes that dynamic branch testable
            // without coupling the migration module to Flowable bootstrapping.
            statement.executeUpdate("""
                    CREATE TABLE act_ru_execution (
                        id_ VARCHAR(64) PRIMARY KEY,
                        tenant_id_ VARCHAR(64) NOT NULL
                    )
                    """);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to prepare Flowable RLS fixture", exception);
        }
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
            assertThat(resultSet.getInt(1)).isEqualTo(34);
        }
        assertThat(tableExists("workflow_node_assignment_rule")).isTrue();
        assertThat(tableExists("auth_user_tenant")).isTrue();
        assertThat(tableExists("workflow_assignment_fallback_command")).isTrue();
        assertThat(tableExists("platform_security_audit")).isTrue();
        assertThat(tableExists("workflow_operation_audit")).isTrue();
        assertThat(tableExists("auth_attempt_guard")).isTrue();
        assertThat(tableExists("agent_definition")).isTrue();
        assertThat(tableExists("agent_definition_version")).isTrue();
        assertThat(tableExists("agent_provider")).isTrue();
        assertThat(tableExists("agent_run")).isTrue();
        assertThat(tableExists("agent_model_invocation")).isTrue();
        assertThat(tableExists("agent_recovery_decision")).isTrue();
        assertThat(tableExists("agent_run_operation")).isTrue();
        assertThat(columnExists("platform_outbox_event", "claim_expires_at")).isTrue();
        assertThat(columnExists("platform_outbox_event", "dead_lettered_at")).isTrue();
        assertThat(columnExists("platform_outbox_event", "resolution_reason")).isTrue();
        assertThat(columnExists("platform_outbox_event", "resolution_method")).isTrue();
        assertThat(columnExists("platform_outbox_event", "resolved_by")).isTrue();
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

    @Test
    void enablesForcedRowLevelSecurityForTenantTables() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM pg_class
                     WHERE relname IN ('workflow_tenant', 'agent_run', 'workflow_node_assignment_rule')
                       AND relrowsecurity = TRUE
                       AND relforcerowsecurity = TRUE
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(3);
        }
    }

    @Test
    void enablesForcedRowLevelSecurityForFlowableTenantTables() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM pg_class table_info
                     JOIN information_schema.columns columns
                       ON columns.table_schema = 'public'
                      AND columns.table_name = table_info.relname
                      AND columns.column_name = 'tenant_id_'
                     WHERE table_info.relname LIKE 'act_%'
                       AND (table_info.relrowsecurity = FALSE
                            OR table_info.relforcerowsecurity = FALSE)
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isZero();
        }
    }

    @Test
    void deniesFlowableRowsWithoutTrustedTenantAndHidesOtherTenantRows() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("SET app.system_worker = 'true'");
            statement.executeUpdate("""
                    INSERT INTO act_ru_execution (id_, tenant_id_)
                    VALUES ('execution-a', 'tenant-a'), ('execution-b', 'tenant-b')
                    """);
            statement.execute("RESET app.system_worker");

            assertThat(countFlowableRows(statement, null)).isZero();
            assertThat(countFlowableRows(statement, "tenant-a")).isEqualTo(1);
            assertThat(countFlowableRows(statement, "tenant-b")).isEqualTo(1);
            assertThat(countFlowableRows(statement, "tenant-missing")).isZero();
        }
    }

    private int countFlowableRows(Statement statement, String tenantCode) throws SQLException {
        statement.execute("RESET app.tenant_code");
        if (tenantCode != null) {
            statement.execute("SET app.tenant_code = '" + tenantCode + "'");
        }
        try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM act_ru_execution")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    @Test
    void rejectsCrossTenantAgentVersionProviderBinding() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO workflow_tenant (tenant_id, tenant_code, tenant_name)
                    VALUES ('agent-tenant-a', 'agent-tenant-a', 'Agent Tenant A'),
                           ('agent-tenant-b', 'agent-tenant-b', 'Agent Tenant B')
                    ON CONFLICT (tenant_code) DO NOTHING
                    """);
            ResultSet providerResult = statement.executeQuery("""
                    INSERT INTO agent_provider
                        (tenant_code, provider_code, provider_name, provider_type)
                    VALUES ('agent-tenant-a', 'mock-a', 'Mock A', 'MOCK')
                    RETURNING id
                    """);
            assertThat(providerResult.next()).isTrue();
            long providerId = providerResult.getLong(1);
            ResultSet definitionResult = statement.executeQuery("""
                    INSERT INTO agent_definition
                        (tenant_code, agent_code, agent_name)
                    VALUES ('agent-tenant-b', 'agent-b', 'Agent B')
                    RETURNING id
                    """);
            assertThat(definitionResult.next()).isTrue();
            long definitionId = definitionResult.getLong(1);

            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO agent_definition_version
                        (tenant_code, definition_id, version, provider_id, system_prompt)
                    VALUES ('agent-tenant-b', %d, 1, %d, 'Prompt')
                    """.formatted(definitionId, providerId)))
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

    private boolean columnExists(String tableName, String columnName) throws SQLException {
        try (Connection connection = connection(); ResultSet columns = connection.getMetaData()
                .getColumns(null, "public", tableName, columnName)) {
            return columns.next();
        }
    }
}
