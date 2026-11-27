package io.github.illuseahashmap.agent.runtime.application.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;
import io.github.illuseahashmap.agent.definition.domain.AgentFailurePolicy;
import io.github.illuseahashmap.agent.definition.domain.AgentVersionStatus;
import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderPort;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderRepository;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry;
import io.github.illuseahashmap.agent.runtime.application.checkpoint.CheckpointContextPolicy;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.InvokeRequest;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Receipt;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.ToolIntent;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.agent.runtime.application.port.GraphDispatchPort.Claim;
import io.github.illuseahashmap.agent.runtime.application.port.GraphDispatchPort.Observation;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcAgentRunExecutionRepository;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcAgentToolGovernanceRepository;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCognitionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphExecutionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphReferenceStore;
import io.github.illuseahashmap.agent.runtime.interfaces.rest.GraphGatewayProtocol;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * Executes actual V65-V69 on isolated PostgreSQL. Earlier tables are a minimal fixture, not full
 * Flyway upgrade acceptance.
 */
class GraphPersistenceIntegrationTest {
    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startDatabase() {
        String configuredUrl = System.getenv("WORKFLOW_LANGGRAPH_JDBC_URL");
        if (configuredUrl == null || configuredUrl.isBlank()) {
            postgres = new PostgreSQLContainer<>("postgres:17-alpine");
            postgres.start();
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
    }

    private final GraphGatewayCodec codec = new GraphGatewayCodec();
    private final java.util.concurrent.atomic.AtomicReference<String> lastModelInput =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<String> lastModelPrompt =
            new java.util.concurrent.atomic.AtomicReference<>();
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private NamedParameterJdbcTemplate named;
    private JdbcGraphExecutionStore executions;
    private JdbcGraphReferenceStore references;
    private String schema;
    private String url;
    private String user;
    private String password;
    private AgentDefinitionVersionRepository completionVersions;
    private AgentToolRegistry completionTools;

    @BeforeEach
    void initialize() throws Exception {
        url =
                postgres == null
                        ? System.getenv("WORKFLOW_LANGGRAPH_JDBC_URL")
                        : postgres.getJdbcUrl();
        user =
                postgres == null
                        ? System.getenv().getOrDefault("WORKFLOW_LANGGRAPH_JDBC_USER", "postgres")
                        : postgres.getUsername();
        password =
                postgres == null
                        ? System.getenv().getOrDefault("WORKFLOW_LANGGRAPH_JDBC_PASSWORD", "")
                        : postgres.getPassword();
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "graph_store_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        named =
                new NamedParameterJdbcTemplate(
                        new DriverManagerDataSource(
                                url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                                user,
                                password));
        db = named.getJdbcTemplate();
        db.execute(
                """
                CREATE TABLE agent_definition_version(id BIGINT,tenant_code TEXT,status TEXT,tool_set_json JSONB,PRIMARY KEY(id,tenant_code));
                CREATE TABLE agent_run(id BIGINT,tenant_code TEXT,agent_version_id BIGINT,status TEXT,current_attempt_id BIGINT,
                    deadline_at TIMESTAMPTZ,available_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    input_snapshot_json JSONB,requested_by TEXT,process_instance_id TEXT,PRIMARY KEY(id,tenant_code),
                    execution_id TEXT DEFAULT 'exec',activity_id TEXT DEFAULT 'agent',activity_activation_id TEXT DEFAULT 'activation',
                    output_mapping_json JSONB DEFAULT '{}',process_failure_policy TEXT DEFAULT 'HOLD',trigger_type TEXT DEFAULT 'FLOWABLE',
                    idempotency_key TEXT DEFAULT 'fixture',lease_owner TEXT,lease_expires_at TIMESTAMPTZ,started_at TIMESTAMPTZ,
                    completed_at TIMESTAMPTZ,created_at TIMESTAMPTZ DEFAULT clock_timestamp(),updated_at TIMESTAMPTZ DEFAULT clock_timestamp(),
                    result_status TEXT,error_code TEXT,output_snapshot_json JSONB,pause_requested_at TIMESTAMPTZ);
                CREATE TABLE agent_run_attempt(id BIGSERIAL PRIMARY KEY,tenant_code TEXT,agent_run_id BIGINT,attempt_no INT,
                    status TEXT,started_at TIMESTAMPTZ,completed_at TIMESTAMPTZ,updated_at TIMESTAMPTZ,error_code TEXT,
                    UNIQUE(id,tenant_code,agent_run_id));
                CREATE TABLE agent_run_state_history(tenant_code TEXT,agent_run_id BIGINT,attempt_id BIGINT,old_status TEXT,new_status TEXT,
                    reason_code TEXT,operator_type TEXT,operator_id TEXT,trace_id TEXT,created_at TIMESTAMPTZ);
                CREATE TABLE completion_test_outbox(tenant_code TEXT,run_id BIGINT,attempt_id BIGINT,output TEXT);
                CREATE TABLE agent_tool_execution_audit(id BIGSERIAL,tenant_code TEXT,tool_code TEXT,idempotency_key TEXT,status TEXT,output_snapshot TEXT,
                    arguments_hash TEXT,error_code TEXT,trace_id TEXT,created_at TIMESTAMPTZ,claim_owner TEXT,lease_expires_at TIMESTAMPTZ,fencing_token BIGINT,
                    UNIQUE(tenant_code,tool_code,idempotency_key));
                CREATE TABLE agent_tool_definition(tool_code TEXT PRIMARY KEY,tool_name TEXT,input_schema TEXT,read_only BOOLEAN,
                    capability_type TEXT,risk_level TEXT,side_effect BOOLEAN,enabled BOOLEAN);
                CREATE TABLE agent_tool_tenant_grant(tenant_code TEXT,tool_code TEXT,enabled BOOLEAN,PRIMARY KEY(tenant_code,tool_code));
                INSERT INTO agent_tool_definition VALUES('lookup','Lookup','{"type":"object"}',true,'DIRECT','LOW',false,true);
                INSERT INTO agent_tool_tenant_grant VALUES('alpha','lookup',true),('beta','lookup',true);
                INSERT INTO agent_definition_version VALUES(10,'alpha','PUBLISHED','["lookup"]'),(10,'beta','PUBLISHED','["lookup"]');
                INSERT INTO agent_run(id,tenant_code,agent_version_id,status,deadline_at,input_snapshot_json,requested_by,process_instance_id)
                    VALUES(1,'alpha',10,'QUEUED',clock_timestamp()+interval '1 hour','{"word":"purchase-one"}','reviewer','process-one'),
                          (2,'alpha',10,'QUEUED',clock_timestamp()+interval '1 hour','{"word":"purchase-two"}','reviewer','process-two'),
                          (3,'beta',10,'QUEUED',clock_timestamp()+interval '1 hour','{}','reviewer','process-three');
                """);
        db.execute(
                Files.readString(
                        Path.of(
                                "../platform-migrations/src/main/resources/db/migration/platform/V65__add_graph_execution_handoff.sql")));
        db.execute(
                Files.readString(
                        Path.of(
                                "../platform-migrations/src/main/resources/db/migration/platform/V66__add_graph_cognition_receipts.sql")));
        db.execute(
                Files.readString(
                        Path.of(
                                "../platform-migrations/src/main/resources/db/migration/platform/V67__add_graph_capacity_governance.sql")));
        String admissionMigration =
                Files.readString(
                        Path.of(
                                "../platform-migrations/src/main/resources/db/migration/platform/V68__enforce_run_admission_and_bind_call_accounts.sql"));
        new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                named.getJdbcTemplate().getDataSource()))
                .executeWithoutResult(status -> db.execute(admissionMigration));
        db.execute("ALTER TABLE agent_definition_version ADD COLUMN provider_id BIGINT DEFAULT 1");
        db.execute(
                Files.readString(
                        Path.of(
                                "../platform-migrations/src/main/resources/db/migration/platform/V69__add_graph_business_completion.sql")));
        db.execute(
                Files.readString(
                        Path.of(
                                "../platform-migrations/src/main/resources/db/migration/platform/V70__add_graph_v2_intents.sql")));
        db.execute(
                Files.readString(
                        Path.of(
                                "../platform-migrations/src/main/resources/db/migration/platform/V71__add_graph_run_budget_and_stop.sql")));
        db.execute(
                Files.readString(
                        Path.of(
                                "../platform-migrations/src/main/resources/db/migration/platform/V72__add_graph_loop_verdicts.sql")));
        db.execute("ALTER TABLE agent_definition_version ADD COLUMN output_schema TEXT");
        db.execute(
                Files.readString(
                        Path.of(
                                "../platform-migrations/src/main/resources/db/migration/platform/V73__add_graph_checklist_and_correction.sql")));
        db.execute(
                Files.readString(
                        Path.of(
                                "../platform-migrations/src/main/resources/db/migration/platform/V74__add_graph_plan_acceptance.sql")));
        db.execute(
                Files.readString(
                        Path.of(
                                "../platform-migrations/src/main/resources/db/migration/platform/V75__add_graph_physical_call_facts.sql")));
        db.execute(
                Files.readString(
                        Path.of(
                                "../platform-migrations/src/main/resources/db/migration/platform/V76__add_graph_plan_revisions.sql")));
        db.execute(
                Files.readString(
                        Path.of(
                                "../platform-migrations/src/main/resources/db/migration/platform/V77__add_graph_memory_and_result_reads.sql")));
        db.execute(
                Files.readString(
                        Path.of(
                                "../platform-migrations/src/main/resources/db/migration/platform/V78__add_graph_format_repair_policy.sql")));
        db.execute(
                Files.readString(
                        Path.of(
                                "../platform-migrations/src/main/resources/db/migration/platform/V79__add_graph_route_projection.sql")));
        db.execute("CREATE TABLE IF NOT EXISTS workflow_tenant(tenant_code VARCHAR(64) PRIMARY KEY)");
        db.execute("INSERT INTO workflow_tenant VALUES('alpha'),('beta') ON CONFLICT DO NOTHING");
        db.execute(Files.readString(Path.of(
            "../platform-migrations/src/main/resources/db/migration/platform/V80__add_tenant_capacity_configuration.sql")));
        reconnectStores();
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null) {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void admissionCountsBothBackendsPausedRunsAndTerminalRetriesAtomically() {
        assertThat(admitted()).isEqualTo(3);
        executions.reserve("alpha", 1, "a".repeat(64), 10, 20);
        db.update("UPDATE agent_run_admission SET hard_limit=3 WHERE scope='GLOBAL'");
        db.update("UPDATE agent_run SET status='PAUSED' WHERE id=1");
        assertThat(admitted()).isEqualTo(3);
        assertThatThrownBy(() -> insertRun(4, "beta"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("capacity exhausted");
        db.update("UPDATE agent_run SET status='FAILED' WHERE id=2");
        assertThat(admitted()).isEqualTo(2);
        insertRun(4, "beta");
        assertThatThrownBy(() -> db.update("UPDATE agent_run SET status='QUEUED' WHERE id=2"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("capacity exhausted");
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=2", String.class))
                .isEqualTo("FAILED");
        assertThat(admitted()).isEqualTo(3);
        db.update("DELETE FROM agent_run WHERE id=4");
        db.update("UPDATE agent_run SET status='QUEUED' WHERE id=2");
        assertThat(admitted()).isEqualTo(3);
    }

    @Test
    void admissionChargesNeitherConflictNoOpNorRolledBackSubmission() {
        db.update(
                "INSERT INTO agent_run(id,tenant_code,status) VALUES(1,'alpha','QUEUED') ON"
                        + " CONFLICT DO NOTHING");
        assertThat(admitted()).isEqualTo(3);
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                named.getJdbcTemplate().getDataSource()));
        tx.executeWithoutResult(
                status -> {
                    insertRun(4, "beta");
                    status.setRollbackOnly();
                });
        assertThat(admitted()).isEqualTo(3);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_run WHERE id=4", Integer.class))
                .isZero();
        db.update("UPDATE agent_run_admission SET hard_limit=2 WHERE scope='TENANT:alpha'");
        assertThatThrownBy(() -> insertRun(4, "alpha")).isInstanceOf(DataAccessException.class);
        insertRun(5, "beta");
        assertThat(admitted()).isEqualTo(4);
    }

    @Test
    void concurrentNativeSubmissionsCannotOverbookLastGlobalSlot() throws Exception {
        db.update("UPDATE agent_run_admission SET hard_limit=4 WHERE scope='GLOBAL'");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures =
                    java.util.stream.IntStream.range(4, 6)
                            .mapToObj(
                                    id ->
                                            pool.submit(
                                                    () -> {
                                                        start.await();
                                                        try {
                                                            insertRun(id, "beta");
                                                            return true;
                                                        } catch (DataAccessException full) {
                                                            return false;
                                                        }
                                                    }))
                            .toList();
            start.countDown();
            int winners = 0;
            for (var future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        }
        assertThat(admitted()).isEqualTo(4);
    }

    @Test
    void dispatchOnlyClaimsCompatibleArtifacts() {
        executions.reserve("alpha", 1, "a".repeat(64), 10, 20);
        assertThat(executions.claimNext("worker", 90, "b".repeat(64))).isEmpty();
        assertThat(executions.claimNext("worker", 90, "a".repeat(64))).isPresent();
    }

    @Test
    void resourceBindingsShareAccountAndCannotMoveOutstandingCalls() {
        db.update("UPDATE agent_definition_version SET provider_id=2 WHERE tenant_code='beta'");
        var config =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphResourceConfiguration(named);
        var settings =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphResourceConfiguration.Configuration(
                        List.of(
                                new io.github.illuseahashmap.agent.runtime.infrastructure
                                        .persistence.JdbcGraphResourceConfiguration.Resource(
                                        "account:shared", 1)),
                        List.of(
                                new io.github.illuseahashmap.agent.runtime.infrastructure
                                        .persistence.JdbcGraphResourceConfiguration.Binding(
                                        "alpha", "PROVIDER", "1", "account:shared"),
                                new io.github.illuseahashmap.agent.runtime.infrastructure
                                        .persistence.JdbcGraphResourceConfiguration.Binding(
                                        "beta", "PROVIDER", "2", "account:shared")));
        config.configure(settings);
        Claim one = reserveAndClaim(1);
        executions.reserve("beta", 3, "a".repeat(64), 10, 20);
        Claim two = executions.claim("beta", "worker-beta", 90).orElseThrow();
        var invocation =
                new Invocation("MODEL", "model:1", List.of(one.binding().inputRef()), null);
        var first = new InvokeRequest(one.binding(), one.generation(), invocation);
        var second =
                new InvokeRequest(
                        two.binding(),
                        two.generation(),
                        new Invocation(
                                "MODEL", "model:1", List.of(two.binding().inputRef()), null));
        var capacity =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCallCapacityStore(named, executions, codec);
        var permit =
                capacity.acquire(
                                executions.authorize(
                                        one.capability(), one.binding(), one.generation()),
                                invocation,
                                () -> config.apply(first))
                        .orElseThrow();
        assertThat(
                        capacity.acquire(
                                executions.authorize(
                                        two.capability(), two.binding(), two.generation()),
                                second.request(),
                                () -> config.apply(second)))
                .isEmpty();
        var moved =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphResourceConfiguration.Configuration(
                        List.of(
                                new io.github.illuseahashmap.agent.runtime.infrastructure
                                        .persistence.JdbcGraphResourceConfiguration.Resource(
                                        "account:new", 1)),
                        List.of(
                                new io.github.illuseahashmap.agent.runtime.infrastructure
                                        .persistence.JdbcGraphResourceConfiguration.Binding(
                                        "alpha", "PROVIDER", "1", "account:new")));
        assertThatThrownBy(() -> config.configure(moved)).isInstanceOf(GraphGatewayException.class);
        assertThat(config.apply(first)).containsExactly("account:shared");
        capacity.completed(permit);
        config.configure(moved);
        assertThat(config.apply(first)).containsExactly("account:new");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_resource_audit", Integer.class))
                .isEqualTo(2);
        assertThatThrownBy(() -> db.update("DELETE FROM agent_graph_resource_audit"))
                .isInstanceOf(DataAccessException.class);
        db.update("DELETE FROM agent_graph_resource_binding WHERE tenant_code='alpha'");
        assertThatThrownBy(() -> config.apply(first)).isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void mcpAccountComesFromPublishedTenantSnapshotNotToolSuppliedHints() {
        db.execute(
                """
                CREATE TABLE mcp_connector_version(id BIGINT,tenant_code TEXT,connector_id BIGINT);
                CREATE TABLE mcp_tool_catalog_version(id BIGINT,tenant_code TEXT,connector_version_id BIGINT,status TEXT);
                CREATE TABLE mcp_tool_snapshot(id BIGINT,tenant_code TEXT,catalog_version_id BIGINT,tool_name TEXT);
                INSERT INTO mcp_connector_version VALUES(8,'alpha',50);
                INSERT INTO mcp_tool_catalog_version VALUES(9,'alpha',8,'PUBLISHED');
                INSERT INTO mcp_tool_snapshot VALUES(11,'alpha',9,'count_words');
                """);
        var config =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphResourceConfiguration(named);
        config.configure(
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphResourceConfiguration.Configuration(
                        List.of(
                                new io.github.illuseahashmap.agent.runtime.infrastructure
                                        .persistence.JdbcGraphResourceConfiguration.Resource(
                                        "mcp:directory-account", 2)),
                        List.of(
                                new io.github.illuseahashmap.agent.runtime.infrastructure
                                        .persistence.JdbcGraphResourceConfiguration.Binding(
                                        "alpha", "CONNECTOR", "50", "mcp:directory-account"))));
        var claim = reserveAndClaim(1);
        var operation =
                new Invocation(
                        "TOOL",
                        "tool:1",
                        List.of(claim.binding().inputRef()),
                        new ToolIntent("mcp:11:count_words", "arguments:one", "b".repeat(64)));
        assertThat(config.apply(new InvokeRequest(claim.binding(), claim.generation(), operation)))
                .containsExactly("mcp:directory-account");
        var beta = executions.reserve("beta", 3, "a".repeat(64), 10, 20);
        assertThatThrownBy(() -> config.apply(new InvokeRequest(beta, 1, operation)))
                .isInstanceOf(GraphGatewayException.class);
        db.update("UPDATE mcp_tool_catalog_version SET status='DRAFT'");
        assertThatThrownBy(
                        () ->
                                config.apply(
                                        new InvokeRequest(
                                                claim.binding(), claim.generation(), operation)))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void accountSnapshotRemainsBoundedAcrossMultiplePatches() {
        db.update(
                "INSERT INTO agent_graph_call_resource SELECT 'account:'||n,1 FROM"
                        + " generate_series(1,1000) n");
        var config =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphResourceConfiguration(named);
        assertThat(config.read().resources()).hasSize(1000);
        assertThatThrownBy(
                        () ->
                                config.configure(
                                        new io.github.illuseahashmap.agent.runtime.infrastructure
                                                .persistence.JdbcGraphResourceConfiguration
                                                .Configuration(
                                                List.of(
                                                        new io.github.illuseahashmap.agent.runtime
                                                                .infrastructure.persistence
                                                                .JdbcGraphResourceConfiguration
                                                                .Resource("account:extra", 1)),
                                                List.of())))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(config.read().resources()).hasSize(1000);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_resource_audit", Integer.class))
                .isZero();
    }

    private long admitted() {
        return db.queryForObject(
                "SELECT used FROM agent_run_admission WHERE scope='GLOBAL'", Long.class);
    }

    @Test
    void migrationBackfillSeesForcedRlsRowsWithNonSuperuserOwner() throws Exception {
        String role = "migration_" + UUID.randomUUID().toString().replace("-", "");
        String isolated = role + "_schema";
        admin.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
        admin.execute("CREATE SCHEMA " + isolated + " AUTHORIZATION " + role);
        try (var connection = DriverManager.getConnection(url, user, password);
                var stmt = connection.createStatement()) {
            stmt.execute("SET ROLE " + role);
            stmt.execute("SET search_path=" + isolated);
            stmt.execute("CREATE TABLE agent_run(id BIGINT,tenant_code TEXT,status TEXT)");
            stmt.execute(
                    "CREATE TABLE agent_graph_call_resource(resource_key VARCHAR(256) PRIMARY"
                            + " KEY)");
            stmt.execute("INSERT INTO agent_run VALUES(1,'alpha','QUEUED'),(2,'beta','PAUSED')");
            stmt.execute("ALTER TABLE agent_run ENABLE ROW LEVEL SECURITY");
            stmt.execute("ALTER TABLE agent_run FORCE ROW LEVEL SECURITY");
            stmt.execute(
                    "CREATE POLICY tenant_scope ON agent_run"
                            + " USING(current_setting('app.system_worker',true)='true')");
            connection.setAutoCommit(false);
            stmt.execute(
                    Files.readString(
                            Path.of(
                                    "../platform-migrations/src/main/resources/db/migration/platform/V68__enforce_run_admission_and_bind_call_accounts.sql")));
            connection.commit();
            try (var rs =
                    stmt.executeQuery(
                            "SELECT used FROM agent_run_admission WHERE scope='GLOBAL'")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(2);
            }
            try (var rs = stmt.executeQuery("SELECT count(*) FROM agent_run")) {
                rs.next();
                assertThat(rs.getInt(1))
                        .isZero(); // local elevation ended with the migration transaction
            }
            connection.rollback();
        } finally {
            admin.execute("DROP SCHEMA " + isolated + " CASCADE");
            admin.execute("DROP ROLE " + role);
        }
    }

    private void insertRun(long id, String tenant) {
        db.update(
                """
                INSERT INTO agent_run(id,tenant_code,agent_version_id,status,deadline_at,input_snapshot_json,requested_by,process_instance_id)
                VALUES(?,?,10,'QUEUED',clock_timestamp()+interval '1 hour','{}','reviewer',?)
                """,
                id,
                tenant,
                "process-" + id);
    }

    @Test
    void reservationIsIdempotentImmutableAndExcludesNativeWorkers() {
        var binding = executions.reserve("alpha", 1, "a".repeat(64), 10, 20);
        assertThat(executions.reserve("alpha", 1, "a".repeat(64), 10, 20)).isEqualTo(binding);
        assertThatThrownBy(() -> executions.reserve("alpha", 1, "b".repeat(64), 10, 20))
                .isInstanceOf(GraphGatewayException.class);
        db.update("UPDATE agent_run SET status='CANCELLED' WHERE id<>1");
        assertThat(new JdbcAgentRunExecutionRepository(named).lockNextAvailable(Instant.now()))
                .isEmpty();
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_dispatch", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> db.update("UPDATE agent_graph_execution SET principal_id='other'"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void attemptedOrUnpublishedRunsCannotBeAdopted() {
        db.update("INSERT INTO agent_run_attempt(tenant_code,agent_run_id) VALUES ('alpha',1)");
        assertThatThrownBy(() -> executions.reserve("alpha", 1, "a".repeat(64), 10, 20))
                .isInstanceOf(GraphGatewayException.class);
        db.update("UPDATE agent_definition_version SET status='DRAFT' WHERE tenant_code='alpha'");
        assertThatThrownBy(() -> executions.reserve("alpha", 2, "a".repeat(64), 10, 20))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_execution", Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT execution_backend FROM agent_run WHERE id=1", String.class))
                .isEqualTo("NATIVE");
    }

    @Test
    void dispatchInsertFailureCannotLeaveAnAdoptedRunWithoutCommand() {
        db.execute(
                """
                CREATE FUNCTION reject_dispatch_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected dispatch failure' USING ERRCODE='23514'; END $$;
                CREATE TRIGGER reject_dispatch_insert BEFORE INSERT ON agent_graph_dispatch
                    FOR EACH ROW EXECUTE FUNCTION reject_dispatch_insert();
                """);
        assertThatThrownBy(() -> executions.reserve("alpha", 1, "a".repeat(64), 10, 20))
                .isInstanceOf(DataAccessException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_execution", Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT execution_backend FROM agent_run WHERE id=1", String.class))
                .isEqualTo("NATIVE");
    }

    @Test
    void capabilityIsHashedRunBoundAndRedacted() {
        Claim first = reserveAndClaim(1);
        Claim second = reserveAndClaim(2);
        assertThat(
                        executions
                                .authorize(first.capability(), first.binding(), first.generation())
                                .principalId())
                .isEqualTo("reviewer");
        assertThat(
                        db.queryForObject(
                                "SELECT capability_hash FROM agent_graph_execution WHERE run_id=1",
                                String.class))
                .isEqualTo(GraphGatewayCodec.sha256(first.capability()))
                .doesNotContain(first.capability());
        assertThat(first.toString()).doesNotContain(first.capability());
        assertThatThrownBy(
                        () ->
                                executions.authorize(
                                        first.capability(), second.binding(), second.generation()))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(
                        () ->
                                executions.authorize(
                                        "browser-token", first.binding(), first.generation()))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void expiredDeliveryReusesCommandButFencesOldGeneration() {
        Claim old = reserveAndClaim(1);
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1"
                        + " second'");
        assertThat(executions.renew(old, 60)).isFalse();
        reconnectStores();
        Claim replacement = executions.claim("alpha", "worker-two", 60).orElseThrow();
        assertThat(replacement.commandId()).isEqualTo(old.commandId());
        assertThat(replacement.generation()).isEqualTo(old.generation() + 1);
        assertThat(replacement.capability()).isNotEqualTo(old.capability());
        assertThatThrownBy(
                        () ->
                                executions.authorize(
                                        old.capability(), old.binding(), old.generation()))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(executions.acknowledge(old, observed())).isFalse();
        assertThat(executions.defer(old, "checkpoint:old", "wait:old", Instant.now())).isFalse();
        assertThat(executions.renew(replacement, 60)).isTrue();
    }

    @Test
    void deferredWaitReleasesAuthorityAndSurvivesRestartWithoutNewAttempt() {
        Claim old = reserveAndClaim(1);
        assertThat(executions.defer(old, "checkpoint:1", "wait:1", Instant.now().plusSeconds(60)))
                .isTrue();
        assertThatThrownBy(
                        () ->
                                executions.authorize(
                                        old.capability(), old.binding(), old.generation()))
                .isInstanceOf(GraphGatewayException.class);
        reconnectStores();
        assertThat(executions.claim("alpha", "worker-two", 60)).isEmpty();
        db.update(
                "UPDATE agent_graph_dispatch SET available_at=clock_timestamp()-interval '1"
                        + " second'");
        Claim next = executions.claim("alpha", "worker-two", 60).orElseThrow();
        assertThat(next.commandId()).isNotEqualTo(old.commandId());
        assertThat(next.checkpointId()).isEqualTo("checkpoint:1");
        assertThat(next.waitId()).isEqualTo("wait:1");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_run_attempt", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void acknowledgeIsFencedAndCannotCompleteBusinessRun() {
        Claim claim = reserveAndClaim(1);
        assertThat(executions.acknowledge(claim, observed())).isTrue();
        reconnectStores();
        var reconciled = executions.inspect("alpha", 1).orElseThrow();
        assertThat(reconciled.commandId()).isEqualTo(claim.commandId());
        assertThat(reconciled.status()).isEqualTo("ACKNOWLEDGED");
        assertThat(reconciled.observation()).isEqualTo(observed());
        assertThat(reconciled.toString()).doesNotContain(claim.capability());
        assertThat(executions.inspect("beta", 1)).isEmpty();
        assertThat(executions.acknowledge(claim, observed())).isFalse();
        assertThat(executions.claim("alpha", "worker-two", 60)).isEmpty();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("RUNNING");
        assertThat(
                        db.queryForObject(
                                "SELECT observation_json FROM agent_graph_dispatch", String.class))
                .contains("CANDIDATE_READY");
        var wrongDatabase =
                new NamedParameterJdbcTemplate(new DriverManagerDataSource(url, user, password));
        assertThatThrownBy(() -> new JdbcGraphReferenceStore(wrongDatabase, executions, codec))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellationRevocationAndInputChangesInvalidateAuthority() {
        Claim claim = reserveAndClaim(1);
        db.update("UPDATE agent_run SET input_snapshot_json='{}' WHERE id=1");
        assertThatThrownBy(
                        () ->
                                executions.authorize(
                                        claim.capability(), claim.binding(), claim.generation()))
                .isInstanceOf(GraphGatewayException.class);
        db.update(
                "UPDATE agent_run SET input_snapshot_json='{"
                        + "\"word\":\"purchase-one\"}' WHERE id=1");
        db.update("UPDATE agent_run SET status='CANCELLED' WHERE id=1");
        assertThat(executions.renew(claim, 60)).isFalse();
        Claim other = reserveAndClaim(2);
        executions.revoke("alpha", 2);
        assertThatThrownBy(
                        () ->
                                executions.authorize(
                                        other.capability(), other.binding(), other.generation()))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(executions.claim("alpha", "worker-two", 60)).isEmpty();
    }

    @Test
    void concurrentClaimHasExactlyOneWinner() throws Exception {
        executions.reserve("alpha", 1, "a".repeat(64), 10, 20);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                String worker = "worker-" + i;
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return executions.claim("alpha", worker, 60).isPresent();
                                }));
            }
            start.countDown();
            int winners = 0;
            for (var future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        }
    }

    @Test
    void immutableArgumentsCannotCrossRunsOrLogicalSteps() {
        Claim first = reserveAndClaim(1);
        Claim second = reserveAndClaim(2);
        var run = executions.authorize(first.capability(), first.binding(), first.generation());
        String body = "{\"word\":\"one\"}";
        String ref = references.recordArguments(run, "tool:1", "lookup", body);
        assertThat(references.recordArguments(run, "tool:1", "lookup", body)).isEqualTo(ref);
        assertThatThrownBy(() -> references.recordArguments(run, "tool:1", "lookup", "{}"))
                .isInstanceOf(GraphGatewayException.class);
        Invocation invocation = tool(ref, body);
        assertThat(references.arguments(run, invocation).json()).isEqualTo(body);
        var other =
                executions.authorize(second.capability(), second.binding(), second.generation());
        assertThatThrownBy(() -> references.arguments(other, invocation))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(
                        () ->
                                references.arguments(
                                        run,
                                        new Invocation(
                                                "TOOL", "tool:2", List.of(), invocation.tool())))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(() -> db.update("UPDATE agent_graph_reference SET body='{}'"))
                .isInstanceOf(DataAccessException.class);
        executions.revoke("alpha", 1);
        assertThatThrownBy(() -> references.arguments(run, invocation))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void receiptMustPointToSuccessfulExistingLedgerAndReplayKeepsReference() {
        Claim claim = reserveAndClaim(1);
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        String ref = references.recordArguments(run, "tool:1", "lookup", "{}");
        var request = tool(ref, "{}");
        String output = "{\"count\":2}";
        String digest = GraphGatewayCodec.sha256(output);
        assertThatThrownBy(() -> references.recordReceipt(run, request, "1:tool:1:lookup", digest))
                .isInstanceOf(GraphGatewayException.class);
        db.update(
                """
                INSERT INTO agent_tool_execution_audit(tenant_code,tool_code,idempotency_key,status,output_snapshot)
                VALUES('alpha','lookup','1:tool:1:lookup','SUCCEEDED',?)
                """,
                output);
        String receipt = references.recordReceipt(run, request, "1:tool:1:lookup", digest);
        reconnectStores();
        assertThat(references.recordReceipt(run, request, "1:tool:1:lookup", digest))
                .isEqualTo(receipt);
        assertThatThrownBy(() -> references.recordReceipt(run, request, "2:tool:1:lookup", digest))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(
                        () ->
                                references.recordReceipt(
                                        run, request, "1:tool:1:lookup", "f".repeat(64)))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(
                        () ->
                                references.recordReceipt(
                                        run, tool("graph:forged", "{}"), "1:tool:1:lookup", digest))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_reference WHERE"
                                        + " kind='TOOL_RECEIPT'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT body FROM agent_graph_reference WHERE kind='TOOL_RECEIPT'",
                                String.class))
                .startsWith("{\"audit_id\":")
                .contains(",\"idempotency_key\":\"1:tool:1:lookup\",\"output_digest\":");
    }

    @Test
    void referenceInsertRollingPastLeaseExpiryRollsBackEntireTransaction() {
        Claim claim = reserveAndClaim(1);
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        db.execute(
                """
                CREATE FUNCTION delay_graph_reference() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_sleep(1.2); RETURN NEW; END $$;
                CREATE TRIGGER delay_graph_reference BEFORE INSERT ON agent_graph_reference
                    FOR EACH ROW EXECUTE FUNCTION delay_graph_reference();
                """);
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()+interval '1"
                        + " second'");
        long started = System.nanoTime();
        assertThatThrownBy(() -> references.recordArguments(run, "tool:1", "lookup", "{}"))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(System.nanoTime() - started).isGreaterThan(TimeUnit.SECONDS.toNanos(1));
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_reference", Integer.class))
                .isZero();
    }

    @Test
    void actualMigrationEnforcesRlsForNonOwnerRoleWithoutAdminBypass() throws Exception {
        reserveAndClaim(1);
        reserveAndClaim(2);
        executions.reserve("beta", 3, "a".repeat(64), 10, 20);
        String role = "gr_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
        try {
            admin.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
            admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA " + schema + " TO " + role);
            admin.execute("GRANT INSERT ON " + schema + ".agent_graph_reference TO " + role);
            admin.execute("GRANT INSERT,UPDATE ON " + schema + ".agent_run TO " + role);
            try (var connection = DriverManager.getConnection(url, user, password);
                    var stmt = connection.createStatement()) {
                stmt.execute("SET ROLE " + role);
                stmt.execute("SET app.platform_admin='true'");
                try (var rs =
                        stmt.executeQuery(
                                "SELECT count(*) FROM " + schema + ".agent_graph_execution")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
                stmt.execute("SET app.tenant_code='alpha'");
                stmt.execute(
                        "INSERT INTO "
                                + schema
                                + ".agent_run(id,tenant_code,status) VALUES(4,'alpha','QUEUED')");
                assertThat(admitted()).isEqualTo(4);
                try (var rs =
                        stmt.executeQuery(
                                "SELECT count(*) FROM " + schema + ".agent_run_admission")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
                stmt.execute("UPDATE " + schema + ".agent_run SET status='FAILED' WHERE id=4");
                assertThat(admitted()).isEqualTo(3);
                try (var rs =
                        stmt.executeQuery(
                                "SELECT count(*) FROM "
                                        + schema
                                        + ".agent_graph_capacity_policy")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
                try (var rs =
                        stmt.executeQuery(
                                "SELECT count(*) FROM " + schema + ".agent_graph_execution")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(2);
                }
                String insert =
                        "INSERT INTO "
                                + schema
                                + ".agent_graph_reference(tenant_code,run_id,reference,binding_digest,kind,operation_id,tool_code,content_digest,body)"
                                + " VALUES ";
                stmt.execute(
                        insert
                                + "('alpha',1,'own-ref','digest','ARGUMENTS','tool:99','lookup','digest','{}')");
                assertThatThrownBy(
                                () ->
                                        stmt.execute(
                                                insert
                                                        + "('beta',3,'foreign-ref','digest','ARGUMENTS','tool:99','lookup','digest','{}')"))
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("row-level security");
            }
        } finally {
            admin.execute("REVOKE ALL ON ALL TABLES IN SCHEMA " + schema + " FROM " + role);
            admin.execute("REVOKE USAGE ON SCHEMA " + schema + " FROM " + role);
            admin.execute("DROP ROLE " + role);
        }
    }

    private Claim reserveAndClaim(long id) {
        executions.reserve("alpha", id, "a".repeat(64), 10, 20);
        return executions.claim("alpha", "worker-one", 60).orElseThrow();
    }

    private void reconnectStores() {
        executions = new JdbcGraphExecutionStore(named, codec);
        references = new JdbcGraphReferenceStore(named, executions, codec);
    }

    private Invocation tool(String ref, String json) {
        return new Invocation(
                "TOOL",
                "tool:1",
                List.of(),
                new ToolIntent("lookup", ref, GraphGatewayCodec.sha256(json)));
    }

    private Observation observed() {
        return new Observation("CANDIDATE_READY", "checkpoint:1", "candidate:1", "validation:1");
    }

    @Test
    void v2MultipleIntentsSurviveReplayReverseExecutionAndCandidateCompletion() {
        var count = new AtomicInteger();
        var gateway =
                cognition(
                        count,
                        n ->
                                n == 1
                                        ? answer(
                                                """
                                                {"action":"TOOL_CALLS","tools":[{"tool":"lookup","arguments":{"word":"one"}},{"tool":"lookup","arguments":{"word":"two"}}]}
                                                """)
                                        : answer("{\"count\":2}"),
                        null,
                        40000);
        executions.reserve("alpha", 1, "b".repeat(64), 10, 20, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var first =
                new Invocation(
                        "MODEL",
                        "model:arbitrary-stable-id",
                        List.of(claim.binding().inputRef()),
                        null);
        var model = invoke(gateway, claim, first);
        assertThat(model.decision().tools()).hasSize(2);
        var a = model.decision().tools().get(0);
        var b = model.decision().tools().get(1);
        assertThat(a.argumentsRef()).startsWith("intent:").isNotEqualTo(b.argumentsRef());
        reconnectStores();
        var restored = cognition(count, n -> answer("{\"count\":2}"), null, 40000);
        assertThat(invoke(restored, claim, first)).isEqualTo(model);
        assertThat(count).hasValue(1);
        var requestB = new Invocation("TOOL", b.argumentsRef(), List.of(), b);
        var receiptB = invoke(restored, claim, requestB);
        var receiptA =
                invoke(restored, claim, new Invocation("TOOL", a.argumentsRef(), List.of(), a));
        assertThat(
                        restored.replay(
                                        claim.capability(),
                                        new InvokeRequest(
                                                claim.binding(), claim.generation(), requestB))
                                .orElseThrow()
                                .receipt())
                .isEqualTo(receiptB);
        assertThatThrownBy(
                        () ->
                                invoke(
                                        restored,
                                        claim,
                                        new Invocation(
                                                "MODEL",
                                                "model:omitted",
                                                List.of(
                                                        claim.binding().inputRef(),
                                                        model.receiptRef()),
                                                null)))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(invoke(restored, claim, requestB)).isEqualTo(receiptB);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isEqualTo(2);
        var next =
                new Invocation(
                        "MODEL",
                        "model:another-id",
                        List.of(
                                claim.binding().inputRef(),
                                receiptB.receiptRef(),
                                model.receiptRef(),
                                receiptA.receiptRef()),
                        null);
        var candidate = invoke(restored, claim, next);
        var validate =
                new Invocation(
                        "VALIDATE",
                        "validate:candidate-one",
                        List.of(
                                candidate.decision().candidateRef(),
                                candidate.receiptRef(),
                                receiptA.receiptRef(),
                                model.receiptRef(),
                                receiptB.receiptRef()),
                        null);
        var accepted = invoke(restored, claim, validate);
        assertThat(accepted.accepted()).isTrue();
        assertThat(invoke(restored, claim, validate)).isEqualTo(accepted);
        assertThat(
                        executions.acknowledge(
                                claim,
                                new Observation(
                                        "CANDIDATE_READY",
                                        "checkpoint:v2",
                                        candidate.decision().candidateRef(),
                                        accepted.receiptRef())))
                .isTrue();
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT count(*) FROM completion_test_outbox", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_model_call", Integer.class))
                .isZero();
    }

    @Test
    void v2IntentPublicationRollsBackAtomicallyAndRawResponseIsReused() {
        var count = new AtomicInteger();
        var gateway = cognition(count, n -> toolResponse(), null, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 10, 20, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        db.execute(
                """
                CREATE FUNCTION fail_v2_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.receipt_json IS NOT NULL THEN RAISE EXCEPTION 'crash'; END IF; RETURN NEW; END $$;
                CREATE TRIGGER fail_v2 BEFORE UPDATE ON agent_graph_v2_model_call FOR EACH ROW EXECUTE FUNCTION fail_v2_receipt();
                """);
        var first =
                new Invocation("MODEL", "model:stable", List.of(claim.binding().inputRef()), null);
        assertThatThrownBy(() -> invoke(gateway, claim, first))
                .isInstanceOf(DataAccessException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_reference", Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT response_json IS NOT NULL FROM agent_graph_v2_model_call",
                                Boolean.class))
                .isTrue();
        db.execute("DROP TRIGGER fail_v2 ON agent_graph_v2_model_call");
        reconnectStores();
        var restored =
                cognition(
                        count,
                        n -> {
                            throw new AssertionError("raw response must be reused");
                        },
                        null,
                        40000);
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1"
                        + " second'");
        var successor = executions.claim("alpha", "worker-two", 60).orElseThrow();
        assertThat(successor.generation()).isGreaterThan(claim.generation());
        assertThatThrownBy(() -> invoke(restored, claim, first))
                .isInstanceOf(GraphGatewayException.class);
        var receipt = invoke(restored, successor, first);
        assertThat(invoke(restored, successor, first)).isEqualTo(receipt);
        assertThat(count).hasValue(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_reference", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void v2RejectsCrossVersionRequestsForgedIntentAndCandidateReceiptSubstitution() {
        var count = new AtomicInteger();
        var gateway = cognition(count, n -> answer("{\"count\":2}"), null, 40000);
        var binding = executions.reserve("alpha", 1, "b".repeat(64), 10, 20, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var protocol = new GraphGatewayProtocol(gateway, codec);
        var first = new Invocation("MODEL", "model:first", List.of(binding.inputRef()), null);
        assertThat(
                        protocol.exchange(
                                        "POST",
                                        GraphGatewayProtocol.PREFIX + "/invoke",
                                        "Bearer " + claim.capability(),
                                        codec.write(
                                                new InvokeRequest(
                                                        binding, claim.generation(), first)))
                                .status())
                .isEqualTo(400);
        assertThatThrownBy(() -> executions.reserve("alpha", 1, "b".repeat(64), 10, 20))
                .isInstanceOf(GraphGatewayException.class);
        var candidate = invoke(gateway, claim, first);
        var refs = List.of(candidate.decision().candidateRef(), candidate.receiptRef());
        var validation = new Invocation("VALIDATE", "validate:first", refs, null);
        var accepted = invoke(gateway, claim, validation);
        var newer =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "model:new",
                                List.of(binding.inputRef(), candidate.receiptRef()),
                                null));
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "VALIDATE",
                                                "validate:first",
                                                List.of(
                                                        newer.decision().candidateRef(),
                                                        candidate.receiptRef(),
                                                        newer.receiptRef()),
                                                null)))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "TOOL",
                                                "intent:00000000-0000-0000-0000-000000000000",
                                                List.of(),
                                                new ToolIntent(
                                                        "lookup",
                                                        "intent:00000000-0000-0000-0000-000000000000",
                                                        "a".repeat(64)))))
                .isInstanceOf(GraphGatewayException.class);
        // New candidate cannot borrow the older validation's success.
        assertThat(
                        executions.acknowledge(
                                claim,
                                new Observation(
                                        "CANDIDATE_READY",
                                        "checkpoint:v2",
                                        newer.decision().candidateRef(),
                                        accepted.receiptRef())))
                .isTrue();
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT completion_status FROM agent_graph_dispatch WHERE run_id=1",
                                String.class))
                .isEqualTo("BLOCKED");
        assertThat(db.queryForObject("SELECT count(*) FROM completion_test_outbox", Integer.class))
                .isZero();
    }

    @Test
    void v2ReceiptsAreImmutableAndTenantIsolated() throws Exception {
        var count = new AtomicInteger();
        var gateway = cognition(count, n -> answer("{\"count\":2}"), null, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 10, 20, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var candidate =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL", "model:first", List.of(claim.binding().inputRef()), null));
        invoke(
                gateway,
                claim,
                new Invocation(
                        "VALIDATE",
                        "validate:first",
                        List.of(candidate.decision().candidateRef(), candidate.receiptRef()),
                        null));
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE agent_graph_v2_model_call SET"
                                                + " operation_id='changed'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () -> db.update("UPDATE agent_graph_v2_model_call SET response_json='{}'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.update("DELETE FROM agent_graph_v2_validation"))
                .isInstanceOf(DataAccessException.class);
        String role = "gr_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
        try {
            admin.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
            admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA " + schema + " TO " + role);
            try (var c = DriverManager.getConnection(url, user, password);
                    var stmt = c.createStatement()) {
                stmt.execute("SET ROLE " + role);
                stmt.execute("SET app.tenant_code='beta'");
                for (var table :
                        List.of(
                                "agent_graph_v2_model_call",
                                "agent_graph_v2_validation",
                                "agent_graph_run_budget",
                                "agent_graph_budget_call",
                                "agent_graph_budget_usage",
                                "agent_graph_loop_policy")) {
                    try (var rs =
                            stmt.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
                        rs.next();
                        assertThat(rs.getInt(1)).isZero();
                    }
                }
                stmt.execute("SET app.tenant_code='alpha'");
                for (var table :
                        List.of(
                                "agent_graph_v2_model_call",
                                "agent_graph_v2_validation",
                                "agent_graph_run_budget",
                                "agent_graph_budget_call",
                                "agent_graph_budget_usage",
                                "agent_graph_loop_policy")) {
                    try (var rs =
                            stmt.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
                        rs.next();
                        assertThat(rs.getInt(1)).isEqualTo(1);
                    }
                }
            }
        } finally {
            admin.execute("REVOKE ALL ON ALL TABLES IN SCHEMA " + schema + " FROM " + role);
            admin.execute("REVOKE USAGE ON SCHEMA " + schema + " FROM " + role);
            admin.execute("DROP ROLE " + role);
        }
    }

    @Test
    void durableModelToolModelValidationUsesExistingRegistryAndNeverCompletesRun() {
        var calls = new AtomicInteger();
        GraphCognitionGateway gateway =
                cognition(
                        calls, n -> n == 1 ? toolResponse() : answer("{\"count\":2}"), null, 40000);
        db.update(
                "UPDATE agent_run SET"
                    + " input_snapshot_json='{\"input\":{\"amount\":20000.000000000001,\"word\":\"hi\"}}'"
                    + " WHERE id=1");
        Claim claim = reserveAndClaim(1);
        Invocation first =
                new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null);
        Receipt model = invoke(gateway, claim, first);
        assertThat(lastModelInput.get()).contains("20000.000000000001");
        Receipt tool =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "TOOL", "tool:1", List.of(), model.decision().tools().getFirst()));
        Invocation second =
                new Invocation(
                        "MODEL",
                        "model:2",
                        List.of(claim.binding().inputRef(), model.receiptRef(), tool.receiptRef()),
                        null);
        Receipt candidate = invoke(gateway, claim, second);
        Invocation validation =
                new Invocation(
                        "VALIDATE",
                        "validate:1",
                        List.of(
                                candidate.decision().candidateRef(),
                                model.receiptRef(),
                                tool.receiptRef(),
                                candidate.receiptRef()),
                        null);
        Receipt accepted = invoke(gateway, claim, validation);
        assertThat(accepted.accepted()).isTrue();
        reconnectStores();
        GraphCognitionGateway restored =
                cognition(
                        calls,
                        n -> {
                            throw new AssertionError("must not re-answer");
                        },
                        null,
                        4096);
        assertThat(invoke(restored, claim, second)).isEqualTo(candidate);
        assertThat(invoke(restored, claim, validation)).isEqualTo(accepted);
        assertThat(calls).hasValue(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit WHERE"
                                        + " status='SUCCEEDED'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("RUNNING");
    }

    @Test
    void rawResponseSurvivesFailureBeforeDecisionAndDoesNotNeedModelAgain() {
        var calls = new AtomicInteger();
        var gateway = cognition(calls, n -> answer("{\"count\":2}"), null, 40000);
        Claim claim = reserveAndClaim(1);
        db.execute(
                """
                CREATE FUNCTION fail_model_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.receipt_json IS NOT NULL THEN RAISE EXCEPTION 'injected crash'; END IF; RETURN NEW; END $$;
                CREATE TRIGGER fail_model_receipt BEFORE UPDATE ON agent_graph_model_call FOR EACH ROW EXECUTE FUNCTION fail_model_receipt();
                """);
        Invocation request =
                new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null);
        assertThatThrownBy(() -> invoke(gateway, claim, request))
                .isInstanceOf(DataAccessException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT response_json IS NOT NULL AND receipt_json IS NULL FROM"
                                        + " agent_graph_model_call",
                                Boolean.class))
                .isTrue();
        db.execute("DROP TRIGGER fail_model_receipt ON agent_graph_model_call");
        reconnectStores();
        var restored =
                cognition(
                        calls,
                        n -> {
                            throw new AssertionError("must replay raw response");
                        },
                        null,
                        4096);
        assertThat(invoke(restored, claim, request).decision().candidateRef()).isNotBlank();
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectedModelCannotCallToolOrEnterRepairAndRejectionIsDurable() {
        var calls = new AtomicInteger();
        var rejected =
                new ModelProviderResponse(
                        "not JSON",
                        "fixture",
                        null,
                        "business_rejected",
                        1,
                        1,
                        0,
                        1,
                        new ModelProviderResponse.ToolCall("lookup", "{}", "call"));
        var gateway = cognition(calls, n -> rejected, "{\"type\":\"object\"}", 40000);
        Claim claim = reserveAndClaim(1);
        Receipt candidate =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL", "model:1", List.of(claim.binding().inputRef()), null));
        assertThat(candidate.decision().tools()).isEmpty();
        Invocation request =
                new Invocation(
                        "VALIDATE",
                        "validate:1",
                        List.of(candidate.decision().candidateRef(), candidate.receiptRef()),
                        null);
        Receipt failure = invoke(gateway, claim, request);
        assertThat(failure.accepted()).isFalse();
        assertThat(invoke(gateway, claim, request)).isEqualTo(failure);
        assertThat(calls).hasValue(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isZero();
    }

    @Test
    void missingProcurementCitationCannotApproveAmountAbove20000() {
        var calls = new AtomicInteger();
        String schema =
                """
                {"type":"object","properties":{"decision":{"type":"string"},"citations":{"type":"array","items":{"type":"string"}}},
                 "x-workflow-citation-claim":{"version":"1","amountField":"amount","above":20000,"decisionField":"decision",
                     "disposition":"CONDITIONAL","citationsField":"citations","requiredChunks":["necessary-clause"]}}
                """;
        var gateway =
                cognition(
                        calls,
                        n ->
                                answer(
                                        "{\"decision\":\"MANDATORY\",\"citations\":[\"necessary-clause\"]}"),
                        schema,
                        40000);
        Claim claim = reserveAndClaim(1);
        Receipt candidate =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL", "model:1", List.of(claim.binding().inputRef()), null));
        assertThat(
                        invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "VALIDATE",
                                                "validate:1",
                                                List.of(
                                                        candidate.decision().candidateRef(),
                                                        candidate.receiptRef()),
                                                null))
                                .accepted())
                .isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT reason_code FROM agent_graph_validation", String.class))
                .isEqualTo("AGENT_DECISION_UNVERIFIED");
    }

    @Test
    void cognitionRejectsOmittedOrForeignReceiptsAndStopsAfterCandidate() {
        var calls = new AtomicInteger();
        var gateway = cognition(calls, n -> answer("{\"count\":2}"), null, 40000);
        Claim one = reserveAndClaim(1);
        Claim two = reserveAndClaim(2);
        Receipt candidate =
                invoke(
                        gateway,
                        one,
                        new Invocation(
                                "MODEL", "model:1", List.of(one.binding().inputRef()), null));
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        two,
                                        new Invocation(
                                                "VALIDATE",
                                                "validate:1",
                                                List.of(
                                                        candidate.decision().candidateRef(),
                                                        candidate.receiptRef()),
                                                null)))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        one,
                                        new Invocation(
                                                "VALIDATE",
                                                "validate:1",
                                                List.of(candidate.decision().candidateRef()),
                                                null)))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        one,
                                        new Invocation(
                                                "MODEL",
                                                "model:2",
                                                List.of(
                                                        one.binding().inputRef(),
                                                        candidate.receiptRef()),
                                                null)))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void duplicateStatusCannotHideBusinessRejection() {
        var calls = new AtomicInteger();
        String ambiguous =
                "{\"resultStatus\":\"REJECTED\",\"resultStatus\":\"SUCCESS\",\"count\":2}";
        var gateway = cognition(calls, n -> answer(ambiguous), "{\"type\":\"object\"}", 40000);
        Claim claim = reserveAndClaim(1);
        Receipt candidate =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL", "model:1", List.of(claim.binding().inputRef()), null));
        assertThat(
                        invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "VALIDATE",
                                                "validate:1",
                                                List.of(
                                                        candidate.decision().candidateRef(),
                                                        candidate.receiptRef()),
                                                null))
                                .accepted())
                .isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT reason_code FROM agent_graph_validation", String.class))
                .isEqualTo("AGENT_RESULT_STATUS_INVALID");
        assertThat(calls).hasValue(1);
    }

    @Test
    void responseThatCannotBeReadAfterRestartCannotBeCommitted() {
        Claim claim = reserveAndClaim(1);
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        var store = new JdbcGraphCognitionStore(named, executions, codec);
        var request = new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null);
        var started = store.beginModel(run, request, "a".repeat(64));
        assertThatThrownBy(
                        () ->
                                store.saveResponse(
                                        run, request, started.ticket(), answer("x".repeat(70000))))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT response_json IS NULL FROM agent_graph_model_call",
                                Boolean.class))
                .isTrue();
    }

    @Test
    void modelReplyAfterRevocationCannotPublishResponseOrToolIntent() throws Exception {
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var gateway =
                cognition(
                        calls,
                        n -> {
                            entered.countDown();
                            try {
                                if (!released.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("release required");
                                }
                            } catch (InterruptedException failure) {
                                throw new AssertionError(failure);
                            }
                            return toolResponse();
                        },
                        null,
                        40000);
        Claim claim = reserveAndClaim(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result =
                    pool.submit(
                            () ->
                                    invoke(
                                            gateway,
                                            claim,
                                            new Invocation(
                                                    "MODEL",
                                                    "model:1",
                                                    List.of(claim.binding().inputRef()),
                                                    null)));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                executions.revoke("alpha", 1);
            } finally {
                released.countDown();
            }
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(GraphGatewayException.class);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT response_json IS NULL FROM agent_graph_model_call",
                                Boolean.class))
                .isTrue();
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_reference", Integer.class))
                .isZero();
    }

    @Test
    void noRawResponseRequiresNewGenerationAndPhysicalBudgetIncludesRetries() {
        var binding = executions.reserve("alpha", 1, "a".repeat(64), 1, 20);
        Claim claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var run = executions.authorize(claim.capability(), binding, claim.generation());
        var store = new JdbcGraphCognitionStore(named, executions, codec);
        var request = new Invocation("MODEL", "model:1", List.of(binding.inputRef()), null);
        var started = store.beginModel(run, request, "a".repeat(64));
        assertThat(started.ticket()).isNotBlank();
        assertThatThrownBy(() -> store.beginModel(run, request, "a".repeat(64)))
                .hasMessage("MODEL_IN_PROGRESS");
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1"
                        + " second'");
        Claim next = executions.claim("alpha", "worker-two", 60).orElseThrow();
        var newRun = executions.authorize(next.capability(), binding, next.generation());
        assertThatThrownBy(() -> store.beginModel(newRun, request, "a".repeat(64)))
                .hasMessage("MODEL_BUDGET_EXHAUSTED");
        assertThatThrownBy(() -> store.saveResponse(run, request, started.ticket(), answer("old")))
                .hasMessage("AUTHORITY_DENIED");
    }

    @Test
    void revokedToolResultsCannotBeReusedForModelOrValidation() {
        var calls = new AtomicInteger();
        var gateway =
                cognition(
                        calls, n -> n == 1 ? toolResponse() : answer("{\"count\":2}"), null, 40000);
        Claim claim = reserveAndClaim(1);
        Receipt model =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL", "model:1", List.of(claim.binding().inputRef()), null));
        Receipt tool =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "TOOL", "tool:1", List.of(), model.decision().tools().getFirst()));
        var second =
                new Invocation(
                        "MODEL",
                        "model:2",
                        List.of(claim.binding().inputRef(), model.receiptRef(), tool.receiptRef()),
                        null);
        Receipt candidate = invoke(gateway, claim, second);
        db.update("UPDATE agent_tool_tenant_grant SET enabled=false WHERE tenant_code='alpha'");
        assertThatThrownBy(() -> invoke(gateway, claim, second))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "VALIDATE",
                                                "validate:1",
                                                List.of(
                                                        candidate.decision().candidateRef(),
                                                        model.receiptRef(),
                                                        tool.receiptRef(),
                                                        candidate.receiptRef()),
                                                null)))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(calls).hasValue(2);
    }

    void pythonGraphRecovery(int version) throws Exception {
        pythonGraphRecovery(version, false, false);
    }

    void pythonGraphRecovery(int version, boolean routed, boolean repair) throws Exception {
        long runId = 100 + (UUID.randomUUID().getMostSignificantBits() & 0xFFFFFFFFFFFFL);
        insertRun(runId, "alpha");
        var calls = new AtomicInteger();
        int routeCalls = routed ? (repair ? 2 : 1) : 0;
        var gateway =
                cognition(
                        calls,
                        n -> {
                            if (routed && n <= routeCalls) {
                                return answer(
                                        repair && n == 1
                                                ? "invalid route"
                                                : routeBody("DIRECT", "[\"lookup\"]"));
                            }
                            return n == routeCalls + 1 ? toolResponse() : answer("{\"count\":2}");
                        },
                        routed ? ROUTED : null,
                        40000);
        // Checkpoint identity is tenant + Run, so each isolated fixture needs its own Run ID.
        executions.reserve("alpha", runId, "a".repeat(64), 10, 20, version);
        Claim claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var protocol =
                new io.github.illuseahashmap.agent.runtime.interfaces.rest.GraphGatewayProtocol(
                        gateway, codec);
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 8);
        var workers = Executors.newFixedThreadPool(4);
        server.setExecutor(workers);
        var droppedCandidate = new java.util.concurrent.atomic.AtomicBoolean();
        var droppedValidation = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext(
                "/internal/agent-runtime/v" + version,
                exchange -> {
                    try (exchange) {
                        byte[] body =
                                exchange.getRequestBody()
                                        .readNBytes(GraphGatewayCodec.MAX_WIRE_BYTES + 1);
                        var response =
                                protocol.exchange(
                                        exchange.getRequestMethod(),
                                        exchange.getRequestURI().getPath(),
                                        exchange.getRequestHeaders().getFirst("Authorization"),
                                        body);
                        var root = codec.read(body, com.fasterxml.jackson.databind.JsonNode.class);
                        if (response.status() == 200
                                && ((root.path("request").path("kind").asText().equals("MODEL")
                                                && response.status() == 200
                                                && codec.read(
                                                                response.body(),
                                                                com.fasterxml.jackson.databind
                                                                        .JsonNode.class)
                                                        .path("receipt")
                                                        .path("decision")
                                                        .path("candidate_ref")
                                                        .isTextual()
                                                && droppedCandidate.compareAndSet(false, true))
                                        || (root.path("request")
                                                        .path("kind")
                                                        .asText()
                                                        .equals("VALIDATE")
                                                && droppedValidation.compareAndSet(false, true)))) {
                            return;
                        }
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(response.status(), response.body().length);
                        exchange.getResponseBody().write(response.body());
                    }
                });
        server.start();
        try {
            Path log = Files.createTempFile("graph-cognition-", ".log");
            var command =
                    new ProcessBuilder(
                                    "uv",
                                    "run",
                                    "--frozen",
                                    "python",
                                    "tests/java_cognition_probe.py")
                            .directory(Path.of("../agent-runtime-langgraph").toFile())
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile());
            command.environment()
                    .put(
                            "GRAPH_COGNITION_BINDING",
                            new String(
                                    codec.write(claim.binding()),
                                    java.nio.charset.StandardCharsets.UTF_8));
            command.environment()
                    .put("GRAPH_COGNITION_GENERATION", Long.toString(claim.generation()));
            command.environment().put("GRAPH_COGNITION_CAPABILITY", claim.capability());
            command.environment()
                    .put(
                            "GRAPH_COGNITION_URL",
                            "http://127.0.0.1:" + server.getAddress().getPort());
            Process process = command.start();
            try {
                assertThat(process.waitFor(45, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
                assertThat(Files.readString(log)).contains("JAVA_COGNITION_GRAPH_OK");
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
            assertThat(calls).hasValue(2 + routeCalls);
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM "
                                            + (version == 1
                                                    ? "agent_graph_validation"
                                                    : "agent_graph_v2_validation"),
                                    Integer.class))
                    .isEqualTo(1 + routeCalls);
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM agent_tool_execution_audit",
                                    Integer.class))
                    .isEqualTo(1);
            assertThat(
                            db.queryForObject(
                                    "SELECT status FROM agent_run WHERE id=" + runId,
                                    String.class))
                    .isEqualTo("RUNNING");
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
    }

    @Test
    void outstandingIncludesDeferredRevokedAndIdempotentReservations() {
        db.update("UPDATE agent_graph_capacity_policy SET max_outstanding=1,tenant_outstanding=1");
        Claim first = reserveAndClaim(1);
        executions.defer(first, "cp:one", "wait:one", Instant.now().plusSeconds(10));
        assertThat(executions.reserve("alpha", 1, "a".repeat(64), 10, 20))
                .isEqualTo(first.binding());
        assertThatThrownBy(() -> executions.reserve("beta", 3, "a".repeat(64), 10, 20))
                .hasMessage("CAPACITY_EXHAUSTED");
        executions.revoke("alpha", 1);
        assertThatThrownBy(() -> executions.reserve("beta", 3, "a".repeat(64), 10, 20))
                .hasMessage("CAPACITY_EXHAUSTED");
        db.update("UPDATE agent_run SET status='CANCELLED' WHERE id=1");
        assertThat(executions.reserve("beta", 3, "a".repeat(64), 10, 20)).isNotNull();
    }

    @Test
    void twoSchedulersCannotTakeLastGlobalSlotAndShrinkDoesNotEvict() throws Exception {
        executions.reserve("alpha", 1, "a".repeat(64), 10, 20);
        executions.reserve("beta", 3, "a".repeat(64), 10, 20);
        db.update("UPDATE agent_graph_capacity_policy SET max_active=1");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                gate.await();
                                return executions.claimNext("one", 60);
                            });
            var b =
                    pool.submit(
                            () -> {
                                gate.await();
                                return new JdbcGraphExecutionStore(named, codec)
                                        .claimNext("two", 60);
                            });
            gate.countDown();
            var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
            Claim winner = results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            assertThat(executions.renew(winner, 60)).isTrue();
            assertThat(executions.claimNext("third", 60)).isEmpty();
            executions.defer(winner, "cp:one", "wait:one", Instant.now());
            assertThat(executions.claimNext("third", 60).orElseThrow().binding().tenantCode())
                    .isNotEqualTo(winner.binding().tenantCode());
        }
    }

    @Test
    void capacityConfigurationPreservesUsageAndRejectsStaleChanges() {
        var config =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCapacityConfiguration(named);
        var original = config.read("alpha");
        var changed =
                config.updateTenant(
                        "alpha",
                        new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                                .JdbcGraphCapacityConfiguration.TenantUpdate(
                                original.revision(),
                                new io.github.illuseahashmap.agent.runtime.infrastructure
                                        .persistence.JdbcGraphCapacityConfiguration.Limits(
                                        1, 1, 1)),
                        "admin");
        assertThat(changed.usage().outstanding()).isEqualTo(original.usage().outstanding());
        assertThat(changed.effective().calls()).isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT hard_limit FROM agent_run_admission WHERE"
                                    + " scope='TENANT:alpha'",
                                Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                config.updateTenant(
                                        "alpha",
                                        new io.github.illuseahashmap.agent.runtime.infrastructure
                                                .persistence.JdbcGraphCapacityConfiguration
                                                .TenantUpdate(
                                                original.revision(), original.overrides()),
                                        "other-admin"))
                .isInstanceOf(
                        io.github.illuseahashmap.workflow.shared.exception.BusinessException.class);
        db.update("UPDATE agent_run SET status='SUCCEEDED' WHERE id=2");
        assertThatThrownBy(() -> db.update("UPDATE agent_run SET status='QUEUED' WHERE id=2"))
                .isInstanceOf(DataAccessException.class);
        var inherited =
                config.updateTenant(
                        "alpha",
                        new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                                .JdbcGraphCapacityConfiguration.TenantUpdate(
                                changed.revision(),
                                new io.github.illuseahashmap.agent.runtime.infrastructure
                                        .persistence.JdbcGraphCapacityConfiguration.Limits(
                                        null, null, null)),
                        "admin");
        assertThat(inherited.effective()).isEqualTo(inherited.defaults());
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_resource_audit", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void capacityTenantOverridesControlDispatchAndPhysicalCalls() {
        var config =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCapacityConfiguration(named);
        var original = config.read("alpha");
        config.updateTenant(
                "alpha",
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCapacityConfiguration.TenantUpdate(
                        original.revision(),
                        new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                                .JdbcGraphCapacityConfiguration.Limits(null, 1, 1)),
                "admin");
        executions.reserve("alpha", 1, "a".repeat(64), 10, 20);
        executions.reserve("alpha", 2, "a".repeat(64), 10, 20);
        executions.reserve("beta", 3, "a".repeat(64), 10, 20);
        var first = executions.claimNext("first", 60).orElseThrow();
        var second = executions.claimNext("second", 60).orElseThrow();
        assertThat(first.binding().tenantCode()).isEqualTo("alpha");
        assertThat(second.binding().tenantCode()).isEqualTo("beta");
        assertThat(executions.claimNext("third", 60)).isEmpty();
        db.update("INSERT INTO agent_graph_call_resource VALUES('account:quota',32)");
        var capacity =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCallCapacityStore(named, executions, codec);
        var run = executions.authorize(first.capability(), first.binding(), first.generation());
        var request =
                new Invocation("MODEL", "model:one", List.of(first.binding().inputRef()), null);
        var token = capacity.acquire(run, request, List.of("account:quota")).orElseThrow();
        assertThat(config.read("alpha").usage().calls()).isEqualTo(1);
        assertThat(
                        capacity.acquire(
                                run,
                                new Invocation(
                                        "MODEL",
                                        "model:two",
                                        List.of(first.binding().inputRef()),
                                        null),
                                List.of("account:quota")))
                .isEmpty();
        capacity.completed(token);
        assertThat(
                        capacity.acquire(
                                run,
                                new Invocation(
                                        "MODEL",
                                        "model:two",
                                        List.of(first.binding().inputRef()),
                                        null),
                                List.of("account:quota")))
                .isPresent();
    }

    @Test
    void capacityPlatformChangesClampOverridesAndUpdateInheritedAdmission() {
        var config =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCapacityConfiguration(named);
        var original = config.read(null);
        config.updateTenant(
                "alpha",
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCapacityConfiguration.TenantUpdate(
                        original.revision(),
                        new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                                .JdbcGraphCapacityConfiguration.Limits(200, 10, 20)),
                "admin");
        var current = config.read(null);
        var changed =
                config.updatePlatform(
                        new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                                .JdbcGraphCapacityConfiguration.PlatformUpdate(
                                current.revision(),
                                new io.github.illuseahashmap.agent.runtime.infrastructure
                                        .persistence.JdbcGraphCapacityConfiguration.Limits(
                                        100, 8, 16),
                                new io.github.illuseahashmap.agent.runtime.infrastructure
                                        .persistence.JdbcGraphCapacityConfiguration.Limits(
                                        50, 2, 4)),
                        "admin");
        assertThat(config.read("alpha").effective()).isEqualTo(changed.platform());
        assertThat(config.read("beta").effective()).isEqualTo(changed.defaults());
        assertThat(
                        db.queryForObject(
                                "SELECT hard_limit FROM agent_run_admission WHERE"
                                    + " scope='TENANT:alpha'",
                                Integer.class))
                .isEqualTo(100);
        assertThat(
                        db.queryForObject(
                                "SELECT hard_limit FROM agent_run_admission WHERE"
                                    + " scope='TENANT:beta'",
                                Integer.class))
                .isEqualTo(50);
        assertThatThrownBy(
                        () ->
                                config.updateTenant(
                                        "missing",
                                        new io.github.illuseahashmap.agent.runtime.infrastructure
                                                .persistence.JdbcGraphCapacityConfiguration
                                                .TenantUpdate(
                                                changed.revision(),
                                                new io.github.illuseahashmap.agent.runtime
                                                        .infrastructure.persistence
                                                        .JdbcGraphCapacityConfiguration.Limits(
                                                        null, null, null)),
                                        "admin"))
                .isInstanceOf(
                        io.github.illuseahashmap.workflow.shared.exception.BusinessException.class);
    }
    @Test
    void tenantFairnessSurvivesRestartAndSkipsSaturatedTenant() {
        executions.reserve("alpha", 1, "a".repeat(64), 10, 20);
        executions.reserve("alpha", 2, "a".repeat(64), 10, 20);
        executions.reserve("beta", 3, "a".repeat(64), 10, 20);
        db.update("UPDATE agent_graph_capacity_policy SET tenant_active=1");
        var first = executions.claimNext("first", 60).orElseThrow();
        reconnectStores();
        var second = executions.claimNext("second", 60).orElseThrow();
        assertThat(first.binding().tenantCode()).isEqualTo("alpha");
        assertThat(second.binding().tenantCode()).isEqualTo("beta");
        assertThat(executions.claimNext("third", 60)).isEmpty();
        executions.defer(first, "cp:one", "wait:one", Instant.now().plusSeconds(10));
        assertThat(executions.claimNext("third", 60).orElseThrow().binding().runId()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_run_attempt", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void sharedAccountPermitsAreAtomicAndUnknownsStayCountedAfterLeaseExpiry() throws Exception {
        Claim first = reserveAndClaim(1);
        Claim second = reserveAndClaim(2);
        db.update(
                "INSERT INTO agent_graph_call_resource"
                        + " VALUES('account:shared',1),('account:fast',1)");
        var capacity =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCallCapacityStore(named, executions, codec);
        var a = executions.authorize(first.capability(), first.binding(), first.generation());
        var b = executions.authorize(second.capability(), second.binding(), second.generation());
        var operation =
                new Invocation("MODEL", "model:1", List.of(first.binding().inputRef()), null);
        var other = new Invocation("MODEL", "model:1", List.of(second.binding().inputRef()), null);
        String token = capacity.acquire(a, operation, List.of("account:shared")).orElseThrow();
        capacity.uncertain(token);
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1"
                        + " second' WHERE run_id=1");
        Claim newer = executions.claim("alpha", "successor", 60).orElseThrow();
        var current = executions.authorize(newer.capability(), newer.binding(), newer.generation());
        assertThat(capacity.acquire(current, operation, List.of("account:shared"))).isEmpty();
        assertThat(capacity.acquire(b, other, List.of("account:shared"))).isEmpty();
        String fast = capacity.acquire(b, other, List.of("account:fast")).orElseThrow();
        capacity.completed(fast);
        assertThat(capacity.reconcile(token, "receipt:remote-cancel-confirmed")).isTrue();
        assertThat(capacity.reconcile(token, "receipt:duplicate")).isFalse();
        String retry =
                capacity.acquire(current, operation, List.of("account:shared")).orElseThrow();
        capacity.completed(token); // old result cannot release the new token
        assertThat(capacity.acquire(b, other, List.of("account:shared"))).isEmpty();
        capacity.completed(retry);
        assertThat(capacity.acquire(b, other, List.of("account:shared"))).isPresent();
    }

    @Test
    void lastCallPermitRaceAndMultiResourceDenialLeaveNoPartialAllocation() throws Exception {
        Claim one = reserveAndClaim(1);
        Claim two = reserveAndClaim(2);
        db.update(
                "INSERT INTO agent_graph_call_resource"
                        + " VALUES('account:shared',1),('connector:one',1)");
        var capacity =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCallCapacityStore(named, executions, codec);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures =
                    List.of(one, two).stream()
                            .map(
                                    claim ->
                                            pool.submit(
                                                    () -> {
                                                        gate.await();
                                                        return capacity.acquire(
                                                                executions.authorize(
                                                                        claim.capability(),
                                                                        claim.binding(),
                                                                        claim.generation()),
                                                                new Invocation(
                                                                        "MODEL",
                                                                        "model:1",
                                                                        List.of(
                                                                                claim.binding()
                                                                                        .inputRef()),
                                                                        null),
                                                                List.of(
                                                                        "connector:one",
                                                                        "account:shared"));
                                                    }))
                            .toList();
            gate.countDown();
            assertThat(
                            List.of(
                                            futures.get(0).get(10, TimeUnit.SECONDS),
                                            futures.get(1).get(10, TimeUnit.SECONDS))
                                    .stream()
                                    .filter(Optional::isPresent)
                                    .count())
                    .isEqualTo(1);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_call_permit", Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_call_allocation", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void capacityGatewayDefersWithoutCallingModelAndReleasesCompletedEnvelope() {
        var calls = new AtomicInteger();
        var delegate = cognition(calls, n -> answer("{\"count\":2}"), null, 40000);
        Claim first = reserveAndClaim(1);
        Claim second = reserveAndClaim(2);
        db.update("INSERT INTO agent_graph_call_resource VALUES('account:shared',1)");
        var capacity =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCallCapacityStore(named, executions, codec);
        String held =
                capacity.acquire(
                                executions.authorize(
                                        second.capability(), second.binding(), second.generation()),
                                new Invocation(
                                        "MODEL",
                                        "model:1",
                                        List.of(second.binding().inputRef()),
                                        null),
                                List.of("account:shared"))
                        .orElseThrow();
        var gateway =
                new GraphCapacityGateway(
                        delegate,
                        executions,
                        capacity,
                        request -> List.of("account:shared"),
                        codec);
        var request =
                new InvokeRequest(
                        first.binding(),
                        first.generation(),
                        new Invocation(
                                "MODEL", "model:1", List.of(first.binding().inputRef()), null));
        var wait = gateway.invoke(first.capability(), request);
        assertThat(wait.deferred().reason()).isEqualTo("QUOTA");
        assertThat(calls).hasValue(0);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_model_call", Integer.class))
                .isZero();
        capacity.completed(held);
        assertThat(gateway.invoke(first.capability(), request).receipt()).isNotNull();
        assertThat(gateway.invoke(first.capability(), request).receipt()).isNotNull();
        assertThat(calls).hasValue(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_call_permit WHERE"
                                        + " state<>'RELEASED'",
                                Integer.class))
                .isZero();
    }

    private io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                    .JdbcGraphCallCapacityStore
            trackedCapacity() {
        db.update(
                "INSERT INTO agent_graph_call_resource VALUES('account:physical',4) ON CONFLICT DO"
                        + " NOTHING");
        return new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                .JdbcGraphCallCapacityStore(named, executions, codec);
    }

    @Test
    void physicalPlanRedirectRecoversAfterCrashBeforePermitRelease() {
        var calls = new AtomicInteger();
        var delegate = cognition(calls, n -> answer(planBody()), PLANNED, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 8, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var capacity = trackedCapacity();
        var request =
                new Invocation("MODEL", "model:probe", List.of(claim.binding().inputRef()), null);
        capacity.acquireTracked(
                        executions.authorize(
                                claim.capability(), claim.binding(), claim.generation()),
                        request,
                        () -> List.of("account:physical"))
                .orElseThrow();
        assertThatThrownBy(() -> invoke(delegate, claim, request)).hasMessage("PLAN_REQUIRED");
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1"
                        + " second'");
        var successor = executions.claim("alpha", "successor", 60).orElseThrow();
        var governed =
                new GraphCapacityGateway(
                        delegate, executions, capacity, r -> List.of("account:physical"), codec);
        assertThatThrownBy(
                        () ->
                                governed.invoke(
                                        successor.capability(),
                                        new InvokeRequest(
                                                successor.binding(),
                                                successor.generation(),
                                                request)))
                .hasMessage("PLAN_REQUIRED");
        assertThat(calls.get()).isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_call_permit WHERE"
                                        + " state<>'RELEASED'",
                                Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_call_outcome WHERE"
                                        + " outcome='NOT_DISPATCHED'",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_call_dispatch", Integer.class))
                .isZero();
    }

    @Test
    void physicalCorrectionRedirectRecoversWithoutAnotherGrantOrToolSend() {
        var delegate =
                cognition(
                        new AtomicInteger(),
                        n -> toolResponse(),
                        CHECKLIST,
                        40000,
                        r -> "{\"word\":\"hi\",\"approved\":true}");
        executions.reserve("alpha", 1, "b".repeat(64), 8, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new java.util.ArrayList<String>(List.of(claim.binding().inputRef()));
        Invocation blocked = null;
        for (int i = 0; i < 3; i++) {
            var model =
                    invoke(
                            delegate,
                            claim,
                            new Invocation(
                                    "MODEL", "model:physical:" + i, List.copyOf(refs), null));
            refs.add(model.receiptRef());
            var intent = model.decision().tools().getFirst();
            var request = new Invocation("TOOL", intent.argumentsRef(), List.of(), intent);
            if (i < 2) {
                refs.add(invoke(delegate, claim, request).receiptRef());
            } else {
                blocked = request;
            }
        }
        var trigger = blocked;
        var capacity = trackedCapacity();
        capacity.acquireTracked(
                        executions.authorize(
                                claim.capability(), claim.binding(), claim.generation()),
                        trigger,
                        () -> List.of("account:physical"))
                .orElseThrow();
        assertThatThrownBy(() -> invoke(delegate, claim, trigger))
                .hasMessage("CORRECTION_REQUIRED");
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1"
                        + " second'");
        var successor = executions.claim("alpha", "successor", 60).orElseThrow();
        var governed =
                new GraphCapacityGateway(
                        delegate, executions, capacity, r -> List.of("account:physical"), codec);
        assertThatThrownBy(
                        () ->
                                governed.invoke(
                                        successor.capability(),
                                        new InvokeRequest(
                                                successor.binding(),
                                                successor.generation(),
                                                trigger)))
                .hasMessage("CORRECTION_REQUIRED");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_correction", Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_call_permit WHERE"
                                        + " state<>'RELEASED'",
                                Integer.class))
                .isZero();
    }

    @Test
    void physicalReturnedModelFreesSharedCapacityDespitePlanRejection() {
        var calls = new AtomicInteger();
        var delegate =
                cognition(
                        calls,
                        n -> answer(n == 1 ? planBody() : plannedTool().replace("s1", "invented")),
                        PLANNED,
                        40000);
        executions.reserve("alpha", 1, "b".repeat(64), 8, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var proposal =
                invoke(
                        delegate,
                        claim,
                        new Invocation(
                                "MODEL",
                                "plan:" + codec.fingerprint(claim.binding()),
                                List.of(claim.binding().inputRef()),
                                null));
        String candidate = proposal.decision().candidateRef();
        invoke(
                delegate,
                claim,
                new Invocation(
                        "VALIDATE",
                        "plan-validate:"
                                + codec.fingerprint(
                                        List.of(codec.fingerprint(claim.binding()), candidate)),
                        List.of(candidate, proposal.receiptRef()),
                        null));
        var capacity = trackedCapacity();
        db.update(
                "UPDATE agent_graph_call_resource SET max_calls=1 WHERE"
                        + " resource_key='account:physical'");
        var governed =
                new GraphCapacityGateway(
                        delegate, executions, capacity, r -> List.of("account:physical"), codec);
        var action =
                new Invocation(
                        "MODEL",
                        "model:invalid-step",
                        List.of(claim.binding().inputRef(), proposal.receiptRef()),
                        null);
        var request = new InvokeRequest(claim.binding(), claim.generation(), action);
        assertThatThrownBy(() -> governed.invoke(claim.capability(), request))
                .hasMessage("PLAN_DENIED");
        assertThatThrownBy(() -> governed.invoke(claim.capability(), request))
                .hasMessage("PLAN_DENIED");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_call_outcome WHERE"
                                        + " outcome='RETURNED'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_call_permit WHERE"
                                        + " state<>'RELEASED'",
                                Integer.class))
                .isZero();
        executions.reserve("alpha", 2, "b".repeat(64), 8, 8, 2);
        var other = executions.claim("alpha", "other-worker", 60).orElseThrow();
        assertThatThrownBy(
                        () ->
                                governed.invoke(
                                        other.capability(),
                                        new InvokeRequest(
                                                other.binding(),
                                                other.generation(),
                                                new Invocation(
                                                        "MODEL",
                                                        "model:other",
                                                        List.of(other.binding().inputRef()),
                                                        null))))
                .hasMessage("PLAN_REQUIRED");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_call_dispatch d JOIN"
                                        + " agent_graph_budget_call b ON"
                                        + " b.call_attempt_id=d.budget_call_attempt AND"
                                        + " b.tenant_code=d.tenant_code AND b.run_id=d.run_id",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void physicalReturnedToolFreesCapacityBeforeOutputValidation() {
        var delegate = cognition(new AtomicInteger(), n -> toolResponse(), null, 40000, r -> null);
        executions.reserve("alpha", 1, "b".repeat(64), 4, 4, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var model =
                invoke(
                        delegate,
                        claim,
                        new Invocation(
                                "MODEL", "model:tool", List.of(claim.binding().inputRef()), null));
        var intent = model.decision().tools().getFirst();
        var capacity = trackedCapacity();
        var governed =
                new GraphCapacityGateway(
                        delegate, executions, capacity, r -> List.of("account:physical"), codec);
        assertThatThrownBy(
                        () ->
                                governed.invoke(
                                        claim.capability(),
                                        new InvokeRequest(
                                                claim.binding(),
                                                claim.generation(),
                                                new Invocation(
                                                        "TOOL",
                                                        intent.argumentsRef(),
                                                        List.of(),
                                                        intent))))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(db.queryForObject("SELECT outcome FROM agent_graph_call_outcome", String.class))
                .isEqualTo("RETURNED");
        assertThat(db.queryForObject("SELECT state FROM agent_graph_call_permit", String.class))
                .isEqualTo("RELEASED");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_reference WHERE"
                                        + " kind='TOOL_RECEIPT'",
                                Integer.class))
                .isZero();
    }

    @Test
    void physicalLateReturnReleasesOnlyItsEnvelopeAfterAuthorityLoss() {
        cognition(new AtomicInteger(), n -> answer("ok"), null, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 8, 0, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        var capacity = trackedCapacity();
        var first =
                new Invocation("MODEL", "model:first", List.of(claim.binding().inputRef()), null);
        var second =
                new Invocation("MODEL", "model:second", List.of(claim.binding().inputRef()), null);
        String a =
                capacity.acquireTracked(run, first, () -> List.of("account:physical"))
                        .orElseThrow();
        String b =
                capacity.acquireTracked(run, second, () -> List.of("account:physical"))
                        .orElseThrow();
        capacity.dispatching(a, run, first, executions.reserveCall(run, first));
        capacity.dispatching(b, run, second, executions.reserveCall(run, second));
        executions.revoke("alpha", 1);
        assertThat(capacity.quarantineOrphans(10)).isEqualTo(2);
        capacity.returned(a);
        capacity.returned(a);
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM agent_graph_call_permit WHERE token=CAST(? AS"
                                        + " uuid)",
                                String.class,
                                a))
                .isEqualTo("RELEASED");
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM agent_graph_call_permit WHERE token=CAST(? AS"
                                        + " uuid)",
                                String.class,
                                b))
                .isEqualTo("QUARANTINED");
        capacity.completed(b);
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM agent_graph_call_permit WHERE token=CAST(? AS"
                                        + " uuid)",
                                String.class,
                                b))
                .isEqualTo("QUARANTINED");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_budget_call", Integer.class))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_call_outcome", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void physicalUnknownSendCannotBeReleasedByGenerationOrLogicalReceipt() {
        cognition(new AtomicInteger(), n -> answer("ok"), null, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 4, 0, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var capacity = trackedCapacity();
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        var request =
                new Invocation("MODEL", "model:unknown", List.of(claim.binding().inputRef()), null);
        var token =
                capacity.acquireTracked(run, request, () -> List.of("account:physical"))
                        .orElseThrow();
        capacity.dispatching(token, run, request, executions.reserveCall(run, request));
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1"
                        + " second'");
        var successor = executions.claim("alpha", "successor", 60).orElseThrow();
        var current =
                executions.authorize(
                        successor.capability(), successor.binding(), successor.generation());
        capacity.recoverTracked(current, request);
        capacity.recovered(current, request, "receipt:logical");
        assertThat(capacity.acquireTracked(current, request, () -> List.of("account:physical")))
                .isEmpty();
        assertThat(db.queryForObject("SELECT state FROM agent_graph_call_permit", String.class))
                .isEqualTo("QUARANTINED");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_call_outcome", Integer.class))
                .isZero();
    }

    @Test
    void physicalUnsentReleaseFencesOldSenderAndKeepsBudgetCharged() {
        cognition(new AtomicInteger(), n -> answer("ok"), null, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 4, 0, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var capacity = trackedCapacity();
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        var request =
                new Invocation("MODEL", "model:unused", List.of(claim.binding().inputRef()), null);
        var token =
                capacity.acquireTracked(run, request, () -> List.of("account:physical"))
                        .orElseThrow();
        var budget = executions.reserveCall(run, request);
        capacity.finished(token);
        assertThatThrownBy(() -> capacity.dispatching(token, run, request, budget))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "INSERT INTO"
                                            + " agent_graph_call_dispatch(token,tenant_code,run_id)"
                                            + " VALUES(CAST(? AS uuid),'alpha',1)",
                                        token))
                .isInstanceOf(DataAccessException.class);
        assertThat(db.queryForObject("SELECT outcome FROM agent_graph_call_outcome", String.class))
                .isEqualTo("NOT_DISPATCHED");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_budget_call", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> db.update("UPDATE agent_graph_call_permit SET state='ACTIVE'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.update("DELETE FROM agent_graph_call_outcome"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void physicalStoppedUnsentOrphanIsReleasedButLegacyPermitIsNotReclassified() {
        cognition(new AtomicInteger(), n -> answer("ok"), null, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 4, 0, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var capacity = trackedCapacity();
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        var request =
                new Invocation("MODEL", "model:new", List.of(claim.binding().inputRef()), null);
        capacity.acquireTracked(run, request, () -> List.of("account:physical")).orElseThrow();
        capacity.acquire(
                        run,
                        new Invocation(
                                "MODEL", "model:legacy", List.of(claim.binding().inputRef()), null),
                        List.of("account:physical"))
                .orElseThrow();
        executions.stop(run, "GRAPH_MODEL_BUDGET_EXHAUSTED");
        assertThat(capacity.quarantineOrphans(10)).isEqualTo(2);
        assertThat(capacity.quarantineOrphans(10)).isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM agent_graph_call_permit WHERE"
                                        + " tracking_version=1",
                                String.class))
                .isEqualTo("RELEASED");
        assertThat(
                        db.queryForObject(
                                "SELECT state FROM agent_graph_call_permit WHERE"
                                        + " tracking_version=0",
                                String.class))
                .isEqualTo("QUARANTINED");
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE agent_graph_call_permit SET tracking_version=1"
                                                + " WHERE tracking_version=0"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void physicalDispatchRejectsWrongBudgetOrRequestAndFactsStayWorkerOnly() throws Exception {
        cognition(new AtomicInteger(), n -> answer("ok"), null, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 4, 0, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var capacity = trackedCapacity();
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        var request =
                new Invocation("MODEL", "model:bound", List.of(claim.binding().inputRef()), null);
        var other =
                new Invocation("MODEL", "model:other", List.of(claim.binding().inputRef()), null);
        var token =
                capacity.acquireTracked(run, request, () -> List.of("account:physical"))
                        .orElseThrow();
        var wrongBudget = executions.reserveCall(run, other);
        assertThatThrownBy(() -> capacity.dispatching(token, run, request, wrongBudget))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(() -> capacity.dispatching(token, run, other, wrongBudget))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(() -> capacity.returned(token))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_call_dispatch", Integer.class))
                .isZero();
        capacity.dispatching(token, run, request, executions.reserveCall(run, request));
        capacity.returned(token);
        String role = "physical_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
        try {
            admin.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
            admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA " + schema + " TO " + role);
            try (var connection = DriverManager.getConnection(url, user, password);
                    var stmt = connection.createStatement()) {
                stmt.execute("SET ROLE " + role);
                stmt.execute("SET app.tenant_code='alpha'");
                stmt.execute("SET app.platform_admin='true'");
                stmt.execute("SET app.system_worker='false'");
                for (String table :
                        List.of("agent_graph_call_dispatch", "agent_graph_call_outcome")) {
                    try (var rs =
                            stmt.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
                        rs.next();
                        assertThat(rs.getInt(1)).isZero();
                    }
                }
                stmt.execute("SET app.system_worker='true'");
                for (String table :
                        List.of("agent_graph_call_dispatch", "agent_graph_call_outcome")) {
                    try (var rs =
                            stmt.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
                        rs.next();
                        assertThat(rs.getInt(1)).isEqualTo(1);
                    }
                }
            }
        } finally {
            admin.execute("REVOKE ALL ON ALL TABLES IN SCHEMA " + schema + " FROM " + role);
            admin.execute("REVOKE USAGE ON SCHEMA " + schema + " FROM " + role);
            admin.execute("DROP ROLE " + role);
        }
        assertThatThrownBy(() -> db.update("DELETE FROM agent_graph_call_dispatch"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void physicalSendAndReleaseRaceHasExactlyOneSafeOutcome() throws Exception {
        cognition(new AtomicInteger(), n -> answer("ok"), null, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 4, 0, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var capacity = trackedCapacity();
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        var request =
                new Invocation("MODEL", "model:race", List.of(claim.binding().inputRef()), null);
        var token =
                capacity.acquireTracked(run, request, () -> List.of("account:physical"))
                        .orElseThrow();
        var budget = executions.reserveCall(run, request);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var sent =
                    pool.submit(
                            () -> {
                                start.await();
                                try {
                                    capacity.dispatching(token, run, request, budget);
                                    return true;
                                } catch (GraphGatewayException fenced) {
                                    return false;
                                }
                            });
            var closed =
                    pool.submit(
                            () -> {
                                start.await();
                                capacity.finished(token);
                                return true;
                            });
            start.countDown();
            boolean dispatched = sent.get(10, java.util.concurrent.TimeUnit.SECONDS);
            closed.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM agent_graph_call_dispatch",
                                    Integer.class))
                    .isEqualTo(dispatched ? 1 : 0);
            assertThat(db.queryForObject("SELECT state FROM agent_graph_call_permit", String.class))
                    .isEqualTo(dispatched ? "QUARANTINED" : "RELEASED");
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM agent_graph_call_outcome", Integer.class))
                    .isEqualTo(dispatched ? 0 : 1);
        }
    }

    @Test
    void physicalSynchronousReturnAfterRevocationStillFreesEnvelope() {
        var delegate =
                cognition(
                        new AtomicInteger(),
                        n -> {
                            executions.revoke("alpha", 1);
                            return answer("ok");
                        },
                        null,
                        40000);
        var claim = reserveAndClaim(1);
        var capacity = trackedCapacity();
        var governed =
                new GraphCapacityGateway(
                        delegate, executions, capacity, r -> List.of("account:physical"), codec);
        assertThatThrownBy(
                        () ->
                                governed.invoke(
                                        claim.capability(),
                                        new InvokeRequest(
                                                claim.binding(),
                                                claim.generation(),
                                                new Invocation(
                                                        "MODEL",
                                                        "model:1",
                                                        List.of(claim.binding().inputRef()),
                                                        null))))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(db.queryForObject("SELECT outcome FROM agent_graph_call_outcome", String.class))
                .isEqualTo("RETURNED");
        assertThat(db.queryForObject("SELECT state FROM agent_graph_call_permit", String.class))
                .isEqualTo("RELEASED");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_call_dispatch WHERE"
                                        + " budget_call_attempt IS NULL",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void capacityMissingPolicyAndArgumentConflictsFailClosed() {
        Claim claim = reserveAndClaim(1);
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        var capacity =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCallCapacityStore(named, executions, codec);
        var operation =
                new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null);
        assertThatThrownBy(() -> capacity.acquire(run, operation, List.of("missing:account")))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_call_permit", Integer.class))
                .isZero();
        db.update("INSERT INTO agent_graph_call_resource VALUES('account:shared',1)");
        String token = capacity.acquire(run, operation, List.of("account:shared")).orElseThrow();
        capacity.completed(token);
        assertThatThrownBy(
                        () ->
                                capacity.acquire(
                                        run,
                                        new Invocation(
                                                "MODEL", "model:1", List.of("input:changed"), null),
                                        List.of("account:shared")))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void durableResponsesBypassUnknownCapacityAndReconcileWithoutNewExternalCall() {
        var calls = new AtomicInteger();
        var delegate = cognition(calls, n -> toolResponse(), null, 40000);
        Claim claim = reserveAndClaim(1);
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        db.update("INSERT INTO agent_graph_call_resource VALUES('account:shared',1)");
        var capacity =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCallCapacityStore(named, executions, codec);
        var operation =
                new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null);
        String token = capacity.acquire(run, operation, List.of("account:shared")).orElseThrow();
        var committed = invoke(delegate, claim, operation);
        capacity.uncertain(token); // crash/lost acknowledgement after response was committed
        var governed =
                new GraphCapacityGateway(
                        delegate,
                        executions,
                        capacity,
                        request -> List.of("account:shared"),
                        codec);
        assertThat(
                        governed.invoke(
                                        claim.capability(),
                                        new InvokeRequest(
                                                claim.binding(), claim.generation(), operation))
                                .receipt())
                .isEqualTo(committed);
        assertThat(calls).hasValue(1);
        var tool =
                new Invocation(
                        "TOOL", "tool:1", List.of(), committed.decision().tools().getFirst());
        String toolToken = capacity.acquire(run, tool, List.of("account:shared")).orElseThrow();
        invoke(delegate, claim, tool);
        capacity.uncertain(toolToken);
        assertThat(
                        governed.invoke(
                                        claim.capability(),
                                        new InvokeRequest(
                                                claim.binding(), claim.generation(), tool))
                                .receipt())
                .isNotNull();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_call_permit WHERE"
                                        + " state<>'RELEASED'",
                                Integer.class))
                .isZero();
        db.update("UPDATE agent_tool_tenant_grant SET enabled=false");
        assertThatThrownBy(
                        () ->
                                governed.invoke(
                                        claim.capability(),
                                        new InvokeRequest(
                                                claim.binding(), claim.generation(), tool)))
                .isInstanceOf(GraphGatewayException.class)
                .hasMessage("TOOL_REJECTED");
    }

    @Test
    void recoveryClassifiesOrphansButNeverFreesUnknownRemoteCapacity() {
        Claim claim = reserveAndClaim(1);
        db.update("INSERT INTO agent_graph_call_resource VALUES('account:shared',1)");
        var capacity =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCallCapacityStore(named, executions, codec);
        String token =
                capacity.acquire(
                                executions.authorize(
                                        claim.capability(), claim.binding(), claim.generation()),
                                new Invocation(
                                        "MODEL",
                                        "model:1",
                                        List.of(claim.binding().inputRef()),
                                        null),
                                List.of("account:shared"))
                        .orElseThrow();
        assertThat(capacity.quarantineOrphans(10)).isZero();
        executions.revoke("alpha", 1);
        assertThat(capacity.quarantineOrphans(10)).isEqualTo(1);
        assertThat(capacity.quarantineOrphans(10)).isZero();
        assertThat(db.queryForObject("SELECT state FROM agent_graph_call_permit", String.class))
                .isEqualTo("QUARANTINED");
        capacity.completed(token); // a definitive late completion may settle its OWN envelope
        assertThat(db.queryForObject("SELECT state FROM agent_graph_call_permit", String.class))
                .isEqualTo("RELEASED");
    }

    @Test
    void lockedTenantDoesNotKeepOtherTenantsBehindHeadOfQueue() throws Exception {
        executions.reserve("alpha", 1, "a".repeat(64), 10, 20);
        executions.reserve("beta", 3, "a".repeat(64), 10, 20);
        try (var connection =
                DriverManager.getConnection(
                        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        user,
                        password)) {
            connection.setAutoCommit(false);
            try (var stmt = connection.createStatement()) {
                stmt.executeQuery("SELECT id FROM agent_run WHERE id=1 FOR UPDATE");
                assertThat(executions.claimNext("worker", 60)).isEmpty();
                assertThat(executions.claimNext("worker", 60).orElseThrow().binding().tenantCode())
                        .isEqualTo("beta");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void uncertainProviderFailureDefersNewCallsButIndependentAccountContinues() {
        var calls = new AtomicInteger();
        var delegate =
                cognition(
                        calls,
                        n -> {
                            if (n == 1) {
                                throw new IllegalStateException(
                                        "connection reset after possible submission");
                            }
                            return answer("{\"count\":2}");
                        },
                        null,
                        40000);
        Claim first = reserveAndClaim(1);
        Claim second = reserveAndClaim(2);
        db.update(
                "INSERT INTO agent_graph_call_resource"
                        + " VALUES('account:slow',1),('account:fast',1)");
        var capacity =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCallCapacityStore(named, executions, codec);
        var gateway =
                new GraphCapacityGateway(
                        delegate,
                        executions,
                        capacity,
                        request ->
                                List.of(
                                        request.binding().runId() == 1
                                                ? "account:slow"
                                                : "account:fast"),
                        codec);
        var one =
                new InvokeRequest(
                        first.binding(),
                        first.generation(),
                        new Invocation(
                                "MODEL", "model:1", List.of(first.binding().inputRef()), null));
        assertThatThrownBy(() -> gateway.invoke(first.capability(), one))
                .isInstanceOf(IllegalStateException.class);
        assertThat(gateway.invoke(first.capability(), one).deferred().reason()).isEqualTo("QUOTA");
        assertThat(calls).hasValue(1);
        var two =
                new InvokeRequest(
                        second.binding(),
                        second.generation(),
                        new Invocation(
                                "MODEL", "model:1", List.of(second.binding().inputRef()), null));
        assertThat(gateway.invoke(second.capability(), two).receipt()).isNotNull();
        assertThat(calls).hasValue(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_call_permit WHERE"
                                        + " state='QUARANTINED'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void acknowledgedCandidateSurvivesRestartAndCommitsExactlyOneCompletion() {
        var calls = new AtomicInteger();
        var claim = completedGraph(calls, false);
        reconnectStores();
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(
                        db.queryForObject(
                                "SELECT completion_status FROM agent_graph_dispatch WHERE run_id=1",
                                String.class))
                .isEqualTo("COMMITTED");
        assertThat(
                        db.queryForObject(
                                "SELECT output_snapshot_json->>'content' FROM agent_run WHERE id=1",
                                String.class))
                .isEqualTo("{\"count\":2}");
        reconnectStores(); // process dies after commit but before caller receives success
        assertThat(completion(false).completeNext()).isFalse();
        assertThat(executions.acknowledge(claim, observed())).isFalse();
        assertThat(db.queryForObject("SELECT count(*) FROM completion_test_outbox", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_run_attempt", Integer.class))
                .isEqualTo(1);
        assertThat(calls).hasValue(2);
        assertThat(admitted()).isEqualTo(2);
    }

    @Test
    void crashAfterOutboxInsertRollsBackEverythingAndReusesCandidateOnRestart() {
        var calls = new AtomicInteger();
        completedGraph(calls, false);
        assertThatThrownBy(() -> completion(true).completeNext())
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("RUNNING");
        assertThat(
                        db.queryForObject(
                                "SELECT completion_status FROM agent_graph_dispatch WHERE run_id=1",
                                String.class))
                .isNull();
        assertThat(db.queryForObject("SELECT count(*) FROM completion_test_outbox", Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_run_state_history WHERE"
                                        + " new_status='SUCCEEDED'",
                                Integer.class))
                .isZero();
        assertThat(admitted()).isEqualTo(3);
        reconnectStores();
        assertThat(completion(false).completeNext()).isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT completion_failures FROM agent_graph_dispatch WHERE"
                                        + " run_id=1",
                                Integer.class))
                .isEqualTo(1);
        db.update(
                "UPDATE agent_graph_dispatch SET available_at=clock_timestamp()-interval '1 second'"
                        + " WHERE run_id=1");
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void killedDatabaseSessionCannotLeaveHalfCommittedBusinessCompletion() {
        var calls = new AtomicInteger();
        completedGraph(calls, false);
        var store =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCompletionStore(named, executions, codec);
        assertThatThrownBy(
                        () ->
                                store.completeNext(
                                        e ->
                                                new io.github.illuseahashmap.agent.runtime
                                                        .application.port.GraphCompletionPort
                                                        .Outcome(true, "SUCCESS", "ACCEPTED", "{}"),
                                        (run, output) -> {
                                            db.update(
                                                    "INSERT INTO completion_test_outbox"
                                                            + " VALUES(?,?,?,?)",
                                                    run.tenantCode(),
                                                    run.id(),
                                                    run.currentAttemptId(),
                                                    output);
                                            int pid =
                                                    db.queryForObject(
                                                            "SELECT pg_backend_pid()",
                                                            Integer.class);
                                            assertThat(
                                                            admin.queryForObject(
                                                                    "SELECT"
                                                                        + " pg_terminate_backend(?)",
                                                                    Boolean.class,
                                                                    pid))
                                                    .isTrue();
                                            db.queryForObject("SELECT 1", Integer.class);
                                        }))
                .isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("RUNNING");
        assertThat(db.queryForObject("SELECT count(*) FROM completion_test_outbox", Integer.class))
                .isZero();
        reconnectStores();
        db.update(
                "UPDATE agent_graph_dispatch SET available_at=clock_timestamp()-interval '1 second'"
                        + " WHERE run_id=1");
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    void overlappingCompletionWorkersCannotPublishTwice() throws Exception {
        completedGraph(new AtomicInteger(), false);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one =
                    pool.submit(
                            () -> {
                                start.await();
                                return completion(false).completeNext();
                            });
            var two =
                    pool.submit(
                            () -> {
                                start.await();
                                return completion(false).completeNext();
                            });
            start.countDown();
            boolean a = one.get(10, TimeUnit.SECONDS);
            boolean b = two.get(10, TimeUnit.SECONDS);
            assertThat(a ^ b).isTrue();
        }
        assertThat(db.queryForObject("SELECT count(*) FROM completion_test_outbox", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void cancelledRunCannotBeOverwrittenByAcknowledgedResult() {
        completedGraph(new AtomicInteger(), false);
        db.update("UPDATE agent_run SET status='CANCELLED' WHERE id=1");
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("CANCELLED");
        assertThat(
                        db.queryForObject(
                                "SELECT completion_status FROM agent_graph_dispatch WHERE run_id=1",
                                String.class))
                .isEqualTo("DISCARDED");
        assertThat(db.queryForObject("SELECT count(*) FROM completion_test_outbox", Integer.class))
                .isZero();
    }

    @Test
    void workflowActivationAndMappingCannotChangeBetweenReservationAndAcceptance() {
        completedGraph(new AtomicInteger(), false);
        db.update("UPDATE agent_run SET activity_activation_id='different-activation' WHERE id=1");
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT completion_status FROM agent_graph_dispatch WHERE run_id=1",
                                String.class))
                .isEqualTo("BLOCKED");
        assertThat(db.queryForObject("SELECT count(*) FROM completion_test_outbox", Integer.class))
                .isZero();
    }

    @Test
    void inventedValidationReceiptCannotCompleteAndDoesNotStayInHotPollLoop() {
        var claim = reserveAndClaim(1);
        executions.acknowledge(claim, observed());
        var store =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCompletionStore(named, executions, codec);
        assertThat(
                        store.completeNext(
                                e -> {
                                    throw new AssertionError("untrusted evidence");
                                },
                                (r, s) -> {
                                    throw new AssertionError();
                                }))
                .isTrue();
        assertThat(
                        store.completeNext(
                                e -> {
                                    throw new AssertionError();
                                },
                                (r, s) -> {
                                    throw new AssertionError();
                                }))
                .isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT completion_reason FROM agent_graph_dispatch WHERE run_id=1",
                                String.class))
                .isEqualTo("GRAPH_RECEIPT_INVALID");
    }

    @Test
    void revokedToolPermissionAtFinalCommitRejectsWithoutModelRepair() {
        var calls = new AtomicInteger();
        completedGraph(calls, false);
        db.update("UPDATE agent_tool_tenant_grant SET enabled=false WHERE tenant_code='alpha'");
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT error_code FROM agent_run WHERE id=1", String.class))
                .isEqualTo("AGENT_TOOL_NOT_AUTHORIZED");
        assertThat(
                        db.queryForObject(
                                "SELECT output_snapshot_json::text FROM agent_run WHERE id=1",
                                String.class))
                .isNull();
        assertThat(calls).hasValue(2);
    }

    @Test
    void durableBusinessRejectionCannotBecomeSuccessDuringAcceptance() {
        var calls = new AtomicInteger();
        completedGraph(calls, true);
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("FAILED");
        assertThat(
                        db.queryForObject(
                                "SELECT result_status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("REJECTED");
        assertThat(calls).hasValue(2);
    }

    @Test
    void sameTenantForeignObservationIsBlockedButDoesNotBlockValidRun() {
        completedGraph(new AtomicInteger(), false);
        var other = reserveAndClaim(2);
        var foreign = executions.inspect("alpha", 1).orElseThrow().observation();
        executions.acknowledge(other, foreign);
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=2", String.class))
                .isEqualTo("RUNNING");
        assertThat(
                        db.queryForObject(
                                "SELECT completion_status FROM agent_graph_dispatch WHERE run_id=2",
                                String.class))
                .isEqualTo("BLOCKED");
        assertThat(db.queryForObject("SELECT count(*) FROM completion_test_outbox", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void completionDeadlineIsCheckedAfterValidationAndDoesNotPublishLateAnswer() {
        db.update(
                "UPDATE agent_run SET deadline_at=clock_timestamp()+interval '3 seconds' WHERE"
                        + " id=1");
        completedGraph(new AtomicInteger(), false);
        var store =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCompletionStore(named, executions, codec);
        assertThat(
                        store.completeNext(
                                e -> {
                                    long remaining =
                                            e.run().binding().deadlineEpochMs()
                                                    - System.currentTimeMillis();
                                    try {
                                        Thread.sleep(Math.max(0, remaining) + 50);
                                    } catch (InterruptedException failure) {
                                        throw new AssertionError(failure);
                                    }
                                    return new io.github.illuseahashmap.agent.runtime.application
                                            .port.GraphCompletionPort.Outcome(
                                            true, "SUCCESS", "ACCEPTED", "{}");
                                },
                                (run, output) -> {
                                    assertThat(run.status().name()).isEqualTo("TIMED_OUT");
                                    assertThat(output).isNull();
                                }))
                .isTrue();
        assertThat(db.queryForObject("SELECT error_code FROM agent_run WHERE id=1", String.class))
                .isEqualTo("AGENT_DEADLINE_EXCEEDED");
    }

    @Test
    void acknowledgedObservationAndCompletionDecisionAreImmutable() {
        completedGraph(new AtomicInteger(), false);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE agent_graph_dispatch SET observation_json='{}'"
                                                + " WHERE run_id=1"))
                .isInstanceOf(DataAccessException.class);
        completion(false).completeNext();
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE agent_graph_dispatch SET completion_status=NULL"
                                                + " WHERE run_id=1"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void v2BudgetReplaysDoNotChargeAndPolicyCannotChange() {
        var calls = new AtomicInteger();
        var gateway = cognition(calls, n -> answer("{\"count\":2}"), null, 40000);
        var policy =
                new io.github.illuseahashmap.agent.runtime.application.port.GraphRunBudgetPort
                        .Policy(2, 1, 1);
        executions.reserve("alpha", 1, "b".repeat(64), 2, 0, 2, policy);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var request =
                new Invocation("MODEL", "model:budget", List.of(claim.binding().inputRef()), null);
        var receipt = invoke(gateway, claim, request);
        assertThat(invoke(gateway, claim, request)).isEqualTo(receipt);
        assertThat(calls).hasValue(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_budget_call", Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT sum(input_tokens) FROM agent_graph_budget_usage",
                                Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> executions.reserve("alpha", 1, "b".repeat(64), 2, 0, 2))
                .isInstanceOf(GraphGatewayException.class);
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        assertThatThrownBy(
                        () ->
                                executions.admit(
                                        run,
                                        new Invocation(
                                                "MODEL",
                                                "model:budget",
                                                List.of("other:input"),
                                                null)))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_run_stop", Integer.class))
                .isZero();
    }

    @Test
    void v2PhysicalBudgetStopsBeforeSecondProviderCallAndSurvivesRestart() {
        var calls = new AtomicInteger();
        var gateway = cognition(calls, n -> answer("{\"count\":2}"), null, 40000);
        executions.reserve(
                "alpha",
                1,
                "b".repeat(64),
                2,
                0,
                2,
                new io.github.illuseahashmap.agent.runtime.application.port.GraphRunBudgetPort
                        .Policy(2, 1, 1));
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var first =
                new Invocation("MODEL", "model:first", List.of(claim.binding().inputRef()), null);
        var receipt = invoke(gateway, claim, first);
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "MODEL",
                                                "model:second",
                                                List.of(
                                                        claim.binding().inputRef(),
                                                        receipt.receiptRef()),
                                                null)))
                .hasMessage("RUN_STOPPED");
        assertThat(calls).hasValue(1);
        reconnectStores();
        assertThatThrownBy(
                        () ->
                                executions.authorize(
                                        claim.capability(), claim.binding(), claim.generation()))
                .hasMessage("RUN_STOPPED");
        assertThatThrownBy(() -> invoke(gateway, claim, first)).hasMessage("RUN_STOPPED");
        assertThat(executions.renew(claim, 60)).isFalse();
        assertThat(executions.acknowledge(claim, observed())).isFalse();
        assertThat(db.queryForObject("SELECT reason FROM agent_graph_run_stop", String.class))
                .isEqualTo("GRAPH_PHYSICAL_BUDGET_EXHAUSTED");
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT output FROM completion_test_outbox", String.class))
                .isNull();
        assertThat(completion(false).completeNext()).isFalse();
        assertThat(db.queryForObject("SELECT count(*) FROM completion_test_outbox", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void v2ConcurrentLastPhysicalReservationHasOneWinner() throws Exception {
        executions.reserve(
                "alpha",
                1,
                "b".repeat(64),
                2,
                0,
                2,
                new io.github.illuseahashmap.agent.runtime.application.port.GraphRunBudgetPort
                        .Policy(2, 1, 1));
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        var start = new java.util.concurrent.CountDownLatch(1);
        var winners = new AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) {
                int id = i;
                tasks.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        executions.reserveCall(
                                                run,
                                                new Invocation(
                                                        "MODEL",
                                                        "model:race" + id,
                                                        List.of(claim.binding().inputRef()),
                                                        null));
                                        winners.incrementAndGet();
                                    } catch (GraphGatewayException stopped) {
                                        assertThat(stopped.code())
                                                .isEqualTo(GraphGatewayException.Code.RUN_STOPPED);
                                    }
                                    return null;
                                }));
            }
            start.countDown();
            for (var task : tasks) {
                task.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        assertThat(winners).hasValue(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_budget_call", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_run_stop", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void v2UnknownReservationsSurviveGenerationAndLateUsageDoesNotRefund() {
        executions.reserve(
                "alpha",
                1,
                "b".repeat(64),
                2,
                0,
                2,
                new io.github.illuseahashmap.agent.runtime.application.port.GraphRunBudgetPort
                        .Policy(2, 1, 1));
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        var request =
                new Invocation("MODEL", "model:unknown", List.of(claim.binding().inputRef()), null);
        var id = executions.reserveCall(run, request);
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1"
                        + " second'");
        var next = executions.claim("alpha", "replacement", 60).orElseThrow();
        var current = executions.authorize(next.capability(), next.binding(), next.generation());
        assertThatThrownBy(() -> executions.reserveCall(current, request))
                .hasMessage("RUN_STOPPED");
        executions.settleCall(run, id, 11, 12);
        executions.settleCall(run, id, 11, 12);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_budget_usage", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_budget_call", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_run_attempt", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void v2LogicalAndValidationLimitsPersistStopWithoutCallingModel() {
        var calls = new AtomicInteger();
        var gateway = cognition(calls, n -> answer("{\"count\":2}"), null, 40000);
        executions.reserve(
                "alpha",
                1,
                "b".repeat(64),
                2,
                0,
                2,
                new io.github.illuseahashmap.agent.runtime.application.port.GraphRunBudgetPort
                        .Policy(1, 2, 1));
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var receipt =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL", "model:one", List.of(claim.binding().inputRef()), null));
        var refs = List.of(receipt.decision().candidateRef(), receipt.receiptRef());
        var accepted =
                invoke(gateway, claim, new Invocation("VALIDATE", "validate:one", refs, null));
        assertThat(accepted.accepted()).isTrue();
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        claim,
                                        new Invocation("VALIDATE", "validate:two", refs, null)))
                .hasMessage("RUN_STOPPED");
        assertThat(calls).hasValue(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_budget_call", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void v2ToolIntentLimitCommitsStopInsteadOfRollingItBack() {
        var gateway = cognition(new AtomicInteger(), n -> toolResponse(), null, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 2, 0, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "MODEL",
                                                "model:one",
                                                List.of(claim.binding().inputRef()),
                                                null)))
                .hasMessage("RUN_STOPPED");
        assertThat(db.queryForObject("SELECT reason FROM agent_graph_run_stop", String.class))
                .isEqualTo("GRAPH_TOOL_BUDGET_EXHAUSTED");
        assertThat(
                        db.queryForObject(
                                "SELECT response_json IS NOT NULL FROM agent_graph_v2_model_call",
                                Boolean.class))
                .isTrue();
    }

    @Test
    void v2StopOutboxFailureRollsBackCompletionButNeverStopOrPermits() {
        cognition(new AtomicInteger(), n -> answer("ok"), null, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 2, 0, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        var capacity =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCallCapacityStore(named, executions, codec);
        db.update("INSERT INTO agent_graph_call_resource VALUES('account:shared',1)");
        capacity.acquire(
                        run,
                        new Invocation(
                                "MODEL",
                                "model:unknown",
                                List.of(claim.binding().inputRef()),
                                null),
                        () -> List.of("account:shared"))
                .orElseThrow();
        executions.stop(run, "GRAPH_MODEL_BUDGET_EXHAUSTED");
        assertThatThrownBy(() -> completion(true).completeNext())
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("RUNNING");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_run_stop", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM completion_test_outbox", Integer.class))
                .isZero();
        assertThat(completion(false).completeNext()).isFalse();
        db.update(
                "UPDATE agent_graph_execution SET stop_retry_at=clock_timestamp()-interval '1"
                        + " second'");
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT state FROM agent_graph_call_permit", String.class))
                .isEqualTo("ACTIVE");
        assertThatThrownBy(() -> db.update("DELETE FROM agent_graph_run_stop"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.update("UPDATE agent_graph_run_budget SET physical_limit=100"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void v2DeadlineWithoutClaimOrAckCompletesWithoutInventingAttempt() throws Exception {
        cognition(
                new AtomicInteger(),
                n -> {
                    throw new AssertionError("no model");
                },
                null,
                40000);
        db.update(
                "UPDATE agent_run SET deadline_at=clock_timestamp()+interval '1 second' WHERE"
                        + " id=1");
        executions.reserve("alpha", 1, "b".repeat(64), 2, 0, 2);
        Thread.sleep(1100);
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("TIMED_OUT");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_run_attempt", Integer.class))
                .isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM completion_test_outbox", Integer.class))
                .isEqualTo(1);
        assertThat(executions.claim("alpha", "late", 60)).isEmpty();
    }

    @Test
    void v2StopPreventsAcknowledgingSuccessfulCandidate() {
        var gateway = cognition(new AtomicInteger(), n -> answer("{\"count\":2}"), null, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 2, 0, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        var candidate =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL", "model:one", List.of(claim.binding().inputRef()), null));
        var validation =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "VALIDATE",
                                "validate:one",
                                List.of(
                                        candidate.decision().candidateRef(),
                                        candidate.receiptRef()),
                                null));
        // Stop is committed immediately before the ACK; a stopped worker cannot submit success.
        executions.stop(run, "GRAPH_MODEL_BUDGET_EXHAUSTED");
        assertThat(
                        executions.acknowledge(
                                claim,
                                new Observation(
                                        "CANDIDATE_READY",
                                        "checkpoint:stop",
                                        candidate.decision().candidateRef(),
                                        validation.receiptRef())))
                .isFalse();
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("FAILED");
    }

    @Test
    void v2PausedDeadlineDoesNotWaitForWorkerOrCheckpoint() throws Exception {
        cognition(
                new AtomicInteger(),
                n -> {
                    throw new AssertionError("no model");
                },
                null,
                40000);
        db.update(
                "UPDATE agent_run SET deadline_at=clock_timestamp()+interval '1 second' WHERE"
                        + " id=1");
        executions.reserve("alpha", 1, "b".repeat(64), 2, 0, 2);
        executions.claim("alpha", "worker", 60).orElseThrow();
        db.update(
                "UPDATE agent_run SET status='PAUSED',pause_requested_at=clock_timestamp() WHERE"
                        + " id=1");
        Thread.sleep(1100);
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("TIMED_OUT");
        assertThat(db.queryForObject("SELECT status FROM agent_run_attempt", String.class))
                .isEqualTo("TIMED_OUT");
    }

    @Test
    void v2LogicalLimitStopsBeforeSecondModelEvenWithPhysicalCapacity() {
        var calls = new AtomicInteger();
        var gateway = cognition(calls, n -> answer("{\"count\":2}"), null, 40000);
        executions.reserve(
                "alpha",
                1,
                "b".repeat(64),
                2,
                0,
                2,
                new io.github.illuseahashmap.agent.runtime.application.port.GraphRunBudgetPort
                        .Policy(1, 2, 1));
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var receipt =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL", "model:first", List.of(claim.binding().inputRef()), null));
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "MODEL",
                                                "model:second",
                                                List.of(
                                                        claim.binding().inputRef(),
                                                        receipt.receiptRef()),
                                                null)))
                .hasMessage("RUN_STOPPED");
        assertThat(calls).hasValue(1);
        assertThat(db.queryForObject("SELECT reason FROM agent_graph_run_stop", String.class))
                .isEqualTo("GRAPH_LOGICAL_BUDGET_EXHAUSTED");
    }

    @Test
    void v2LateProviderUsageIsRecordedButStoppedAnswerCannotCommit() {
        var authorized =
                new java.util.concurrent.atomic.AtomicReference<
                        io.github.illuseahashmap.agent.runtime.application.port
                                .GraphRunAuthorityPort.AuthorizedRun>();
        var gateway =
                cognition(
                        new AtomicInteger(),
                        n -> {
                            executions.stop(authorized.get(), "GRAPH_MODEL_BUDGET_EXHAUSTED");
                            return answer("{\"count\":2}");
                        },
                        null,
                        40000);
        executions.reserve("alpha", 1, "b".repeat(64), 2, 0, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        authorized.set(
                executions.authorize(claim.capability(), claim.binding(), claim.generation()));
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "MODEL",
                                                "model:late",
                                                List.of(claim.binding().inputRef()),
                                                null)))
                .hasMessage("RUN_STOPPED");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_budget_usage", Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT response_json IS NULL FROM agent_graph_v2_model_call",
                                Boolean.class))
                .isTrue();
        assertThat(completion(false).completeNext()).isTrue();
    }

    @Test
    void v2ConcurrentStopFinalizersPublishOnlyOnce() throws Exception {
        cognition(new AtomicInteger(), n -> answer("ok"), null, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 2, 0, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        executions.stop(
                executions.authorize(claim.capability(), claim.binding(), claim.generation()),
                "GRAPH_MODEL_BUDGET_EXHAUSTED");
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var one =
                    pool.submit(
                            () -> {
                                start.await();
                                return completion(false).completeNext();
                            });
            var two =
                    pool.submit(
                            () -> {
                                start.await();
                                return completion(false).completeNext();
                            });
            start.countDown();
            one.get(10, java.util.concurrent.TimeUnit.SECONDS);
            two.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM completion_test_outbox", Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_stop_completion", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void v2LegacyRetryAndResumeCannotExtendDeadlineClearAttemptOrResurrectStop() {
        cognition(new AtomicInteger(), n -> answer("ok"), null, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 2, 0, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE agent_run SET deadline_at=deadline_at+interval '1"
                                                + " hour' WHERE id=1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(
                        () ->
                                db.update(
                                        "UPDATE agent_run SET"
                                                + " status='QUEUED',current_attempt_id=NULL WHERE"
                                                + " id=1"))
                .isInstanceOf(DataAccessException.class);
        executions.stop(run, "GRAPH_MODEL_BUDGET_EXHAUSTED");
        completion(false).completeNext();
        assertThatThrownBy(() -> db.update("UPDATE agent_run SET status='QUEUED' WHERE id=1"))
                .isInstanceOf(DataAccessException.class);
        // Untouched native Run still uses its legacy deadline policy.
        assertThat(
                        db.update(
                                "UPDATE agent_run SET deadline_at=deadline_at+interval '1 hour'"
                                        + " WHERE id=2"))
                .isEqualTo(1);
    }

    private static final String ROUTED =
            "{\"type\":\"object\",\"x-graph-routing\":{\"version\":1,\"allow_correction\":true}}";

    private String routeBody(String mode, String tools) {
        return "{\"schemaVersion\":\"1\",\"mode\":\""
                + mode
                + "\",\"reason\":\"Choose authorized capabilities\",\"requestedTools\":"
                + tools
                + ",\"steps\":[\"answer\"]}";
    }

    private Invocation routeValidation(Claim claim, Receipt candidate, List<String> refs) {
        var inputs = new ArrayList<>(refs);
        inputs.set(0, candidate.decision().candidateRef());
        return new Invocation(
                "VALIDATE",
                "route-validate:"
                        + codec.fingerprint(
                                List.of(
                                        codec.fingerprint(claim.binding()),
                                        candidate.decision().candidateRef())),
                List.copyOf(inputs),
                null);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"DIRECT", "RAG", "MCP", "RAG_MCP"})
    void routeProjectionUsesNativeCapabilitiesAndReusesFrozenVerdict(String mode) {
        boolean mixed = "RAG_MCP".equals(mode);
        var calls = new AtomicInteger();
        db.update(
                "UPDATE agent_tool_definition SET capability_type=? WHERE tool_code='lookup'",
                mixed ? "RAG" : mode);
        var gateway =
                cognition(
                        calls,
                        n ->
                                n == 1
                                        ? answer(
                                                routeBody(
                                                        mode,
                                                        mixed
                                                                ? "[\"lookup\",\"lookup_extra\"]"
                                                                : "[\"lookup\"]"))
                                        : toolResponse(),
                        ROUTED,
                        40000,
                        r -> "{}",
                        mixed);
        executions.reserve("alpha", 1, "b".repeat(64), 8, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new ArrayList<String>();
        refs.add(claim.binding().inputRef());
        var first = new Invocation("MODEL", "model:initial", List.copyOf(refs), null);
        assertThatThrownBy(() -> invoke(gateway, claim, first)).hasMessage("ROUTE_REQUIRED");
        assertThat(calls).hasValue(0);
        var request =
                new Invocation(
                        "MODEL",
                        "route:" + codec.fingerprint(claim.binding()),
                        List.copyOf(refs),
                        null);
        var proposal = invoke(gateway, claim, request);
        refs.add(proposal.receiptRef());
        var validation = routeValidation(claim, proposal, refs);
        var verdict = invoke(gateway, claim, validation);
        assertThat(verdict.accepted()).isTrue();
        assertThat(invoke(gateway, claim, validation)).isEqualTo(verdict);
        assertThat(invoke(gateway, claim, request)).isEqualTo(proposal);
        assertThat(
                        executions.acknowledge(
                                claim,
                                new Observation(
                                        "CANDIDATE_READY",
                                        "cp:route",
                                        proposal.decision().candidateRef(),
                                        verdict.receiptRef())))
                .isFalse();
        var next =
                invoke(
                        gateway,
                        claim,
                        new Invocation("MODEL", "model:after-route", List.copyOf(refs), null));
        assertThat(next.decision().tools()).hasSize(1);
        var tool = next.decision().tools().getFirst();
        invoke(gateway, claim, new Invocation("TOOL", tool.argumentsRef(), List.of(), tool));
        assertThat(calls).hasValue(2);
        db.update("UPDATE agent_tool_tenant_grant SET enabled=false WHERE tool_code='lookup'");
        assertThatThrownBy(() -> invoke(gateway, claim, request)).hasMessage("AUTHORITY_DENIED");
        assertThat(calls).hasValue(2);
        assertThatThrownBy(() -> db.update("DELETE FROM agent_graph_route_verdict"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void routeProjectionAbstainsWithoutAnotherModelAndCannotMasqueradeAsAnswer() {
        var calls = new AtomicInteger();
        var gateway = cognition(calls, n -> answer(routeBody("ABSTAIN", "[]")), ROUTED, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 8, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new ArrayList<String>();
        refs.add(claim.binding().inputRef());
        var proposal =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "route:" + codec.fingerprint(claim.binding()),
                                List.copyOf(refs),
                                null));
        refs.add(proposal.receiptRef());
        var validation = routeValidation(claim, proposal, refs);
        var verdict = invoke(gateway, claim, validation);
        assertThat(verdict.accepted()).isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT reason_code FROM agent_graph_v2_validation", String.class))
                .isEqualTo("AGENT_ROUTE_ABSTAIN");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_correction", Integer.class))
                .isZero();
        assertThat(
                        executions.acknowledge(
                                claim,
                                new Observation(
                                        "REJECTED",
                                        "cp:abstain",
                                        proposal.decision().candidateRef(),
                                        verdict.receiptRef())))
                .isTrue();
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("FAILED");
        assertThat(calls).hasValue(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void routeProjectionDirectCannotPublishUnselectedTool(boolean batch) {
        var calls = new AtomicInteger();
        var gateway =
                cognition(
                        calls,
                        n ->
                                n == 1
                                        ? answer(routeBody("DIRECT", "[]"))
                                        : batch
                                                ? answer(
                                                        "{\"action\":\"TOOL_CALLS\",\"tools\":[{\"tool\":\"lookup\",\"arguments\":{}}]}")
                                                : toolResponse(),
                        ROUTED,
                        40000);
        executions.reserve("alpha", 1, "b".repeat(64), 8, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new ArrayList<String>();
        refs.add(claim.binding().inputRef());
        var proposal =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "route:" + codec.fingerprint(claim.binding()),
                                List.copyOf(refs),
                                null));
        refs.add(proposal.receiptRef());
        assertThat(invoke(gateway, claim, routeValidation(claim, proposal, refs)).accepted())
                .isTrue();
        var wrong = new ArrayList<>(refs);
        wrong.set(0, proposal.decision().candidateRef());
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "VALIDATE",
                                                "validate:bypass",
                                                List.copyOf(wrong),
                                                null)))
                .isInstanceOf(GraphGatewayException.class);
        var rejected =
                invoke(
                        gateway,
                        claim,
                        new Invocation("MODEL", "model:tool", List.copyOf(refs), null));
        assertThat(rejected.decision().tools()).isEmpty();
        refs.add(rejected.receiptRef());
        refs.set(0, rejected.decision().candidateRef());
        var verdict =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "VALIDATE", "validate:unselected-tool", List.copyOf(refs), null));
        assertThat(verdict.accepted()).isFalse();
        assertThat(
                        executions.acknowledge(
                                claim,
                                new Observation(
                                        "REJECTED",
                                        "cp:unselected",
                                        rejected.decision().candidateRef(),
                                        verdict.receiptRef())))
                .isTrue();
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("FAILED");
        assertThat(calls).hasValue(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isZero();
    }

    @Test
    void routeProjectionRepairSharesOneGrantAndColdReplayDoesNotResend() throws Exception {
        var calls = new AtomicInteger();
        var gateway =
                cognition(
                        calls,
                        n -> answer(n == 1 ? "{broken" : routeBody("DIRECT", "[]")),
                        ROUTED,
                        40000);
        executions.reserve("alpha", 1, "b".repeat(64), 8, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new ArrayList<String>();
        refs.add(claim.binding().inputRef());
        var proposal =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "route:" + codec.fingerprint(claim.binding()),
                                List.copyOf(refs),
                                null));
        refs.add(proposal.receiptRef());
        var validation = routeValidation(claim, proposal, refs);
        db.execute(
                "CREATE FUNCTION fail_route_grant() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN"
                        + " RAISE EXCEPTION ''injected''; END'");
        db.execute(
                "CREATE TRIGGER fail_route_grant BEFORE INSERT ON agent_graph_correction FOR EACH"
                        + " ROW EXECUTE FUNCTION fail_route_grant()");
        assertThatThrownBy(() -> invoke(gateway, claim, validation))
                .isInstanceOf(DataAccessException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_route_verdict", Integer.class))
                .isZero();
        db.execute("DROP TRIGGER fail_route_grant ON agent_graph_correction");
        assertThatThrownBy(() -> invoke(gateway, claim, validation))
                .hasMessage("ROUTE_REPAIR_REQUIRED");
        assertThatThrownBy(() -> invoke(gateway, claim, validation))
                .hasMessage("ROUTE_REPAIR_REQUIRED");
        var repair =
                new Invocation(
                        "MODEL",
                        "route:repair:"
                                + codec.fingerprint(
                                        List.of(
                                                codec.fingerprint(claim.binding()),
                                                codec.fingerprint(validation))),
                        List.copyOf(refs),
                        null);
        var fixed = invoke(gateway, claim, repair);
        reconnectStores();
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1"
                        + " second'");
        var resumed = executions.claim("alpha", "successor", 60).orElseThrow();
        assertThat(invoke(gateway, resumed, repair)).isEqualTo(fixed);
        refs.add(fixed.receiptRef());
        assertThat(invoke(gateway, resumed, routeValidation(resumed, fixed, refs)).accepted())
                .isTrue();
        assertThat(calls).hasValue(2);
        assertThat(lastModelInput.get()).contains("{broken");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_correction", Integer.class))
                .isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "null",
                "{broken",
                "{\"schemaVersion\":\"1\",\"mode\":\"MCP\",\"reason\":\"invalid"
                        + " capability\",\"requestedTools\":[\"lookup\"],\"steps\":[]}"
            })
    void routeProjectionInvalidRepairTerminatesWithoutAnotherGrant(String content) {
        var calls = new AtomicInteger();
        var gateway = cognition(calls, n -> answer(content), ROUTED, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 8, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new ArrayList<String>();
        refs.add(claim.binding().inputRef());
        var proposal =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "route:" + codec.fingerprint(claim.binding()),
                                List.copyOf(refs),
                                null));
        refs.add(proposal.receiptRef());
        var validation = routeValidation(claim, proposal, refs);
        assertThatThrownBy(() -> invoke(gateway, claim, validation))
                .hasMessage("ROUTE_REPAIR_REQUIRED");
        var repair =
                new Invocation(
                        "MODEL",
                        "route:repair:"
                                + codec.fingerprint(
                                        List.of(
                                                codec.fingerprint(claim.binding()),
                                                codec.fingerprint(validation))),
                        List.copyOf(refs),
                        null);
        var fixed = invoke(gateway, claim, repair);
        refs.add(fixed.receiptRef());
        var rejected = invoke(gateway, claim, routeValidation(claim, fixed, refs));
        assertThat(rejected.accepted()).isFalse();
        assertThat(
                        executions.acknowledge(
                                claim,
                                new Observation(
                                        "REJECTED",
                                        "cp:invalid-route",
                                        fixed.decision().candidateRef(),
                                        rejected.receiptRef())))
                .isTrue();
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_correction", Integer.class))
                .isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 8})
    void routeProjectionBudgetAndExplicitRepairOptOutRejectWithoutExtraModel(int models) {
        String policy = models == 1 ? ROUTED : ROUTED.replace("true", "false");
        var calls = new AtomicInteger();
        var gateway = cognition(calls, n -> answer("{broken"), policy, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), models, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new ArrayList<String>();
        refs.add(claim.binding().inputRef());
        var proposal =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "route:" + codec.fingerprint(claim.binding()),
                                List.copyOf(refs),
                                null));
        refs.add(proposal.receiptRef());
        assertThat(invoke(gateway, claim, routeValidation(claim, proposal, refs)).accepted())
                .isFalse();
        assertThat(calls).hasValue(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_correction", Integer.class))
                .isZero();
    }

    @Test
    void routeProjectionAbsentPolicyKeepsLegacyModelFlow() {
        var calls = new AtomicInteger();
        var gateway = cognition(calls, n -> answer("{}"), "{}", 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 8, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = List.of(claim.binding().inputRef());
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "MODEL",
                                                "route:" + codec.fingerprint(claim.binding()),
                                                refs,
                                                null)))
                .hasMessage("ROUTE_DENIED");
        assertThat(
                        invoke(gateway, claim, new Invocation("MODEL", "model:legacy", refs, null))
                                .decision()
                                .candidateRef())
                .isNotNull();
        assertThat(calls).hasValue(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_route_policy", Integer.class))
                .isZero();
    }

    @Test
    void routeProjectionRepairPreventsFinalFormatRepairFromTakingAnotherGrant() {
        String schema =
                CHECKLIST.replace(
                        "\"type\":\"object\"",
                        "\"type\":\"object\",\"x-graph-routing\":{\"version\":1,\"allow_correction\":true},\"x-graph-format\":{\"version\":1}");
        var calls = new AtomicInteger();
        var gateway =
                cognition(
                        calls,
                        n ->
                                n == 2
                                        ? answer(routeBody("DIRECT", "[\"lookup\"]"))
                                        : n == 3 ? toolResponse() : answer("{broken"),
                        schema,
                        40000,
                        r -> "{\"word\":\"hi\",\"approved\":true}");
        executions.reserve("alpha", 1, "b".repeat(64), 10, 10, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new ArrayList<String>();
        refs.add(claim.binding().inputRef());
        var proposal =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "route:" + codec.fingerprint(claim.binding()),
                                List.copyOf(refs),
                                null));
        refs.add(proposal.receiptRef());
        var validation = routeValidation(claim, proposal, refs);
        assertThatThrownBy(() -> invoke(gateway, claim, validation))
                .hasMessage("ROUTE_REPAIR_REQUIRED");
        var repair =
                new Invocation(
                        "MODEL",
                        "route:repair:"
                                + codec.fingerprint(
                                        List.of(
                                                codec.fingerprint(claim.binding()),
                                                codec.fingerprint(validation))),
                        List.copyOf(refs),
                        null);
        var fixed = invoke(gateway, claim, repair);
        refs.add(fixed.receiptRef());
        assertThat(invoke(gateway, claim, routeValidation(claim, fixed, refs)).accepted()).isTrue();
        var action =
                invoke(
                        gateway,
                        claim,
                        new Invocation("MODEL", "model:lookup", List.copyOf(refs), null));
        refs.add(action.receiptRef());
        var intent = action.decision().tools().getFirst();
        refs.add(
                invoke(
                                gateway,
                                claim,
                                new Invocation("TOOL", intent.argumentsRef(), List.of(), intent))
                        .receiptRef());
        var answer =
                invoke(
                        gateway,
                        claim,
                        new Invocation("MODEL", "model:answer", List.copyOf(refs), null));
        refs.add(answer.receiptRef());
        refs.set(0, answer.decision().candidateRef());
        assertThat(
                        invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "VALIDATE",
                                                "validate:answer",
                                                List.copyOf(refs),
                                                null))
                                .accepted())
                .isFalse();
        assertThat(calls).hasValue(4);
        assertThat(db.queryForObject("SELECT reason FROM agent_graph_correction", String.class))
                .isEqualTo("ROUTE_INVALID");
    }

    @Test
    void routeProjectionPrecedesPlanAndFinalAcceptanceRechecksSelectedAuthority() {
        String schema =
                PLANNED.replace(
                        "\"type\":\"object\"",
                        "\"type\":\"object\",\"x-graph-routing\":{\"version\":1,\"allow_correction\":true}");
        var calls = new AtomicInteger();
        var gateway =
                cognition(
                        calls,
                        n ->
                                answer(
                                        switch (n) {
                                            case 1 -> routeBody("DIRECT", "[\"lookup\"]");
                                            case 2 -> planBody();
                                            case 3 -> plannedTool();
                                            default -> "{\"approved\":true}";
                                        }),
                        schema,
                        40000,
                        r -> "{\"word\":\"hi\",\"approved\":true}");
        executions.reserve("alpha", 1, "b".repeat(64), 10, 10, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new ArrayList<String>();
        refs.add(claim.binding().inputRef());
        var proposal =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "route:" + codec.fingerprint(claim.binding()),
                                List.copyOf(refs),
                                null));
        refs.add(proposal.receiptRef());
        assertThat(invoke(gateway, claim, routeValidation(claim, proposal, refs)).accepted())
                .isTrue();
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "MODEL", "model:initial", List.copyOf(refs), null)))
                .hasMessage("PLAN_REQUIRED");
        var plan =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "plan:" + codec.fingerprint(claim.binding()),
                                List.copyOf(refs),
                                null));
        refs.add(plan.receiptRef());
        var planRefs = new ArrayList<>(refs);
        planRefs.set(0, plan.decision().candidateRef());
        assertThat(
                        invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "VALIDATE",
                                                "plan-validate:"
                                                        + codec.fingerprint(
                                                                List.of(
                                                                        codec.fingerprint(
                                                                                claim.binding()),
                                                                        plan.decision()
                                                                                .candidateRef())),
                                                List.copyOf(planRefs),
                                                null))
                                .accepted())
                .isTrue();
        var action =
                invoke(
                        gateway,
                        claim,
                        new Invocation("MODEL", "model:lookup", List.copyOf(refs), null));
        refs.add(action.receiptRef());
        var intent = action.decision().tools().getFirst();
        refs.add(
                invoke(
                                gateway,
                                claim,
                                new Invocation("TOOL", intent.argumentsRef(), List.of(), intent))
                        .receiptRef());
        var answer =
                invoke(
                        gateway,
                        claim,
                        new Invocation("MODEL", "model:answer", List.copyOf(refs), null));
        refs.add(answer.receiptRef());
        refs.set(0, answer.decision().candidateRef());
        var valid =
                invoke(
                        gateway,
                        claim,
                        new Invocation("VALIDATE", "validate:answer", List.copyOf(refs), null));
        assertThat(valid.accepted()).isTrue();
        assertThat(
                        executions.acknowledge(
                                claim,
                                new Observation(
                                        "CANDIDATE_READY",
                                        "cp:routed-plan",
                                        answer.decision().candidateRef(),
                                        valid.receiptRef())))
                .isTrue();
        db.update("UPDATE agent_tool_tenant_grant SET enabled=false");
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("FAILED");
        assertThat(calls).hasValue(4);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void routeProjectionFinalAcceptanceAndPolicyRls(boolean revoke) throws Exception {
        var calls = new AtomicInteger();
        var gateway =
                cognition(
                        calls,
                        n -> answer(n == 1 ? routeBody("DIRECT", "[\"lookup\"]") : "{}"),
                        ROUTED,
                        40000);
        executions.reserve("alpha", 1, "b".repeat(64), 8, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new ArrayList<String>();
        refs.add(claim.binding().inputRef());
        var proposal =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "route:" + codec.fingerprint(claim.binding()),
                                List.copyOf(refs),
                                null));
        refs.add(proposal.receiptRef());
        assertThat(invoke(gateway, claim, routeValidation(claim, proposal, refs)).accepted())
                .isTrue();
        String role = "route_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
        try {
            admin.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
            admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA " + schema + " TO " + role);
            try (var c = DriverManager.getConnection(url, user, password);
                    var stmt = c.createStatement()) {
                stmt.execute("SET ROLE " + role);
                stmt.execute("SET search_path TO " + schema);
                stmt.execute("SET app.system_worker='false'");
                stmt.execute("SET app.tenant_code='beta'");
                for (var table : List.of("agent_graph_route_policy", "agent_graph_route_verdict")) {
                    try (var rows = stmt.executeQuery("SELECT count(*) FROM " + table)) {
                        rows.next();
                        assertThat(rows.getInt(1)).isZero();
                    }
                }
                stmt.execute("SET app.tenant_code='alpha'");
                try (var rows =
                        stmt.executeQuery("SELECT count(*) FROM agent_graph_route_verdict")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                }
            }
        } finally {
            admin.execute("DROP OWNED BY " + role);
            admin.execute("DROP ROLE " + role);
        }
        var answer =
                invoke(
                        gateway,
                        claim,
                        new Invocation("MODEL", "model:answer", List.copyOf(refs), null));
        refs.add(answer.receiptRef());
        refs.set(0, answer.decision().candidateRef());
        var validation =
                invoke(
                        gateway,
                        claim,
                        new Invocation("VALIDATE", "validate:answer", List.copyOf(refs), null));
        assertThat(validation.accepted()).isTrue();
        assertThat(
                        executions.acknowledge(
                                claim,
                                new Observation(
                                        "CANDIDATE_READY",
                                        "cp:routed-answer",
                                        answer.decision().candidateRef(),
                                        validation.receiptRef())))
                .isTrue();
        if (revoke) {
            db.update("UPDATE agent_tool_tenant_grant SET enabled=false");
        }
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo(revoke ? "FAILED" : "SUCCEEDED");
        if (revoke) {
            assertThat(
                            db.queryForObject(
                                    "SELECT error_code FROM agent_run WHERE id=1", String.class))
                    .isEqualTo("GRAPH_ROUTE_AUTHORITY_DENIED");
        }
        assertThat(calls).hasValue(2);
    }

    private static final String CHECKLIST =
            """
            {"type":"object","x-graph-checklist":{"version":1,"subject_field":"word","required_facts":[
              {"id":"approval","tool":"lookup","value_field":"approved","expected":true}],"allow_correction":true}}
            """;

    private static final String PLANNED =
            CHECKLIST.replace(
                    "\"type\":\"object\"",
                    "\"type\":\"object\",\"x-graph-planning\":{\"version\":1,\"mode\":\"PLANNED\"}");

    private String planBody() {
        return new String(
                codec.write(
                        new GraphPlan(
                                "p1",
                                1,
                                0,
                                codec.fingerprint(GraphTaskContract.parseSchema(CHECKLIST, codec)),
                                List.of("hi"),
                                "Check approval",
                                List.of(
                                        new GraphPlan.Step(
                                                "s1",
                                                List.of("approval"),
                                                List.of("lookup"),
                                                List.of(),
                                                List.of("approval"),
                                                "REJECT")))),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private String plannedTool() {
        return "{\"action\":\"TOOL_CALL\",\"tool\":\"lookup\",\"arguments\":{\"word\":\"hi\"},\"plan_id\":\"p1\",\"plan_revision\":1,\"plan_step_id\":\"s1\"}";
    }

    private record FormatFixture(
            GraphCognitionGateway gateway,
            Claim claim,
            ArrayList<String> refs,
            Invocation validation,
            AtomicInteger calls) {}

    private FormatFixture formatFixture(
            boolean enabled, int models, String repaired, boolean evidence) {
        return formatFixture(enabled, models, "{broken", repaired, evidence);
    }

    private FormatFixture formatFixture(
            boolean enabled, int models, String original, String repaired, boolean evidence) {
        String schema =
                enabled
                        ? CHECKLIST.replace(
                                "\"type\":\"object\"",
                                "\"type\":\"object\",\"x-graph-format\":{\"version\":1}")
                        : CHECKLIST;
        var calls = new AtomicInteger();
        var gateway =
                cognition(
                        calls,
                        n -> n == 1 ? toolResponse() : answer(n == 2 ? original : repaired),
                        schema,
                        40000,
                        r -> "{\"word\":\"hi\",\"approved\":" + evidence + "}");
        executions.reserve("alpha", 1, "b".repeat(64), models, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new ArrayList<String>();
        refs.add(claim.binding().inputRef());
        var first =
                invoke(
                        gateway,
                        claim,
                        new Invocation("MODEL", "model:tool", List.copyOf(refs), null));
        refs.add(first.receiptRef());
        var intent = first.decision().tools().getFirst();
        refs.add(
                invoke(
                                gateway,
                                claim,
                                new Invocation("TOOL", intent.argumentsRef(), List.of(), intent))
                        .receiptRef());
        var broken =
                invoke(
                        gateway,
                        claim,
                        new Invocation("MODEL", "model:broken", List.copyOf(refs), null));
        refs.add(broken.receiptRef());
        var inputs = new ArrayList<>(refs);
        inputs.set(0, broken.decision().candidateRef());
        return new FormatFixture(
                gateway,
                claim,
                refs,
                new Invocation("VALIDATE", "validate:broken", List.copyOf(inputs), null),
                calls);
    }

    private Invocation formatRepairRequest(FormatFixture f) {
        return new Invocation(
                "MODEL",
                "repair:"
                        + codec.fingerprint(
                                List.of(
                                        codec.fingerprint(f.claim().binding()),
                                        codec.fingerprint(f.validation()))),
                List.copyOf(f.refs()),
                null);
    }

    @Test
    void formatRepairSharesGrantAndRevalidatesNewCandidateAfterLostAck() {
        var f = formatFixture(true, 8, "{\"approved\":true}", true);
        assertThatThrownBy(() -> invoke(f.gateway(), f.claim(), f.validation()))
                .hasMessage("FORMAT_REPAIR_REQUIRED");
        assertThatThrownBy(() -> invoke(f.gateway(), f.claim(), f.validation()))
                .hasMessage("FORMAT_REPAIR_REQUIRED");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_correction", Integer.class))
                .isEqualTo(1);
        assertThat(executions.acknowledge(f.claim(), observed())).isFalse();
        var request = formatRepairRequest(f);
        var repaired = invoke(f.gateway(), f.claim(), request);
        assertThat(invoke(f.gateway(), f.claim(), request)).isEqualTo(repaired);
        assertThat(f.calls()).hasValue(3);
        assertThat(lastModelInput.get()).contains("{broken", "AGENT_OUTPUT_NOT_JSON");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_correction_result",
                                Integer.class))
                .isEqualTo(1);
        f.refs().add(repaired.receiptRef());
        var inputs = new ArrayList<>(f.refs());
        inputs.set(0, repaired.decision().candidateRef());
        var validation =
                invoke(
                        f.gateway(),
                        f.claim(),
                        new Invocation("VALIDATE", "validate:repaired", List.copyOf(inputs), null));
        assertThat(validation.accepted()).isTrue();
        assertThat(
                        executions.acknowledge(
                                f.claim(),
                                new Observation(
                                        "CANDIDATE_READY",
                                        "cp:repair",
                                        repaired.decision().candidateRef(),
                                        validation.receiptRef())))
                .isTrue();
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("SUCCEEDED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "{broken-again",
                "{\"action\":\"TOOL_CALL\",\"tool\":\"lookup\",\"arguments\":{\"word\":\"hi\"},\"approved\":true}",
                "{\"action\":\"TOOL_CALLS\",\"tools\":[],\"approved\":true}",
                "{\"action\":\"READ_RESULT\",\"approved\":true}"
            })
    void formatRepairCannotGrantASecondRepairOrDispatchTools(String content) {
        var f = formatFixture(true, 8, content, true);
        assertThatThrownBy(() -> invoke(f.gateway(), f.claim(), f.validation()))
                .hasMessage("FORMAT_REPAIR_REQUIRED");
        var repaired = invoke(f.gateway(), f.claim(), formatRepairRequest(f));
        assertThat(repaired.decision().tools()).isEmpty();
        f.refs().add(repaired.receiptRef());
        var inputs = new ArrayList<>(f.refs());
        inputs.set(0, repaired.decision().candidateRef());
        assertThat(
                        invoke(
                                        f.gateway(),
                                        f.claim(),
                                        new Invocation(
                                                "VALIDATE",
                                                "validate:still-invalid",
                                                List.copyOf(inputs),
                                                null))
                                .accepted())
                .isFalse();
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_correction", Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isEqualTo(1);
        assertThat(f.calls()).hasValue(3);
    }

    @Test
    void formatRepairHandlesSchemaFailureAndColdResumeWithoutResending() throws Exception {
        var f = formatFixture(true, 8, "[]", "{\"approved\":true}", true);
        assertThatThrownBy(() -> invoke(f.gateway(), f.claim(), f.validation()))
                .hasMessage("FORMAT_REPAIR_REQUIRED");
        var request = formatRepairRequest(f);
        db.execute(
                "CREATE FUNCTION fail_repair_result() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN"
                        + " RAISE EXCEPTION ''injected''; END'");
        db.execute(
                "CREATE TRIGGER fail_repair_result BEFORE INSERT ON agent_graph_correction_result"
                        + " FOR EACH ROW EXECUTE FUNCTION fail_repair_result()");
        assertThatThrownBy(() -> invoke(f.gateway(), f.claim(), request))
                .isInstanceOf(DataAccessException.class);
        assertThat(f.calls()).hasValue(3);
        db.execute("DROP TRIGGER fail_repair_result ON agent_graph_correction_result");
        reconnectStores();
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1"
                        + " second'");
        var resumed = executions.claim("alpha", "successor", 60).orElseThrow();
        assertThat(resumed.generation()).isGreaterThan(f.claim().generation());
        var repaired = invoke(f.gateway(), resumed, request);
        assertThat(f.calls()).hasValue(3);
        assertThat(lastModelInput.get()).contains("AGENT_OUTPUT_SCHEMA_INVALID");
        db.update("UPDATE agent_tool_tenant_grant SET enabled=false");
        assertThatThrownBy(() -> invoke(f.gateway(), resumed, request))
                .isInstanceOf(GraphGatewayException.class);
        db.update("UPDATE agent_tool_tenant_grant SET enabled=true");
        var inputs = new ArrayList<>(f.refs());
        inputs.add(repaired.receiptRef());
        inputs.set(0, repaired.decision().candidateRef());
        assertThat(
                        invoke(
                                        f.gateway(),
                                        resumed,
                                        new Invocation(
                                                "VALIDATE",
                                                "validate:resumed",
                                                List.copyOf(inputs),
                                                null))
                                .accepted())
                .isTrue();
    }

    @Test
    void formatRepairCannotTakeAnotherGrantAfterLoopCorrection() {
        var calls = new AtomicInteger();
        var gateway =
                cognition(
                        calls,
                        n -> n <= 3 ? toolResponse() : answer("{broken"),
                        CHECKLIST.replace(
                                "\"type\":\"object\"",
                                "\"type\":\"object\",\"x-graph-format\":{\"version\":1}"),
                        40000,
                        r -> "{\"word\":\"hi\",\"approved\":true}");
        executions.reserve("alpha", 1, "b".repeat(64), 10, 10, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new ArrayList<String>();
        refs.add(claim.binding().inputRef());
        Invocation trigger = null;
        for (int i = 0; i < 3; i++) {
            var model =
                    invoke(
                            gateway,
                            claim,
                            new Invocation("MODEL", "model:" + i, List.copyOf(refs), null));
            refs.add(model.receiptRef());
            var intent = model.decision().tools().getFirst();
            var tool = new Invocation("TOOL", intent.argumentsRef(), List.of(), intent);
            if (i < 2) {
                refs.add(invoke(gateway, claim, tool).receiptRef());
            } else {
                trigger = tool;
            }
        }
        var blocked = trigger;
        assertThatThrownBy(() -> invoke(gateway, claim, blocked)).hasMessage("CORRECTION_REQUIRED");
        String id =
                "correction:"
                        + codec.fingerprint(
                                List.of(
                                        codec.fingerprint(claim.binding()),
                                        codec.fingerprint(blocked)));
        var candidate =
                invoke(gateway, claim, new Invocation("MODEL", id, List.copyOf(refs), null));
        refs.add(candidate.receiptRef());
        refs.set(0, candidate.decision().candidateRef());
        assertThat(
                        invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "VALIDATE",
                                                "validate:after-correction",
                                                List.copyOf(refs),
                                                null))
                                .accepted())
                .isFalse();
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_correction", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT reason FROM agent_graph_correction", String.class))
                .isEqualTo("LOOP_DETECTED");
        assertThat(calls).hasValue(4);
    }

    @Test
    void formatRepairOptOutKeepsLegacyRejection() {
        var f = formatFixture(false, 8, "{\"approved\":true}", true);
        assertThat(invoke(f.gateway(), f.claim(), f.validation()).accepted()).isFalse();
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_correction", Integer.class))
                .isZero();
        assertThatThrownBy(() -> invoke(f.gateway(), f.claim(), formatRepairRequest(f)))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(f.calls()).hasValue(2);
    }

    @Test
    void formatRepairDoesNotRepairMissingEvidence() {
        var f = formatFixture(true, 8, "{\"approved\":true}", false);
        assertThat(invoke(f.gateway(), f.claim(), f.validation()).accepted()).isFalse();
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_correction", Integer.class))
                .isZero();
        assertThat(f.calls()).hasValue(2);
    }

    @Test
    void formatRepairNeedsRemainingModelAndValidationBudget() {
        var f = formatFixture(true, 2, "{\"approved\":true}", true);
        assertThat(invoke(f.gateway(), f.claim(), f.validation()).accepted()).isFalse();
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_correction", Integer.class))
                .isZero();
        assertThat(f.calls()).hasValue(2);
    }

    @Test
    void formatRepairGrantAndValidationCommitAtomically() {
        var f = formatFixture(true, 8, "{\"approved\":true}", true);
        db.execute(
                "CREATE FUNCTION fail_format_grant() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN"
                        + " RAISE EXCEPTION ''injected''; END'");
        db.execute(
                "CREATE TRIGGER fail_format_grant BEFORE INSERT ON agent_graph_correction FOR EACH"
                        + " ROW EXECUTE FUNCTION fail_format_grant()");
        assertThatThrownBy(() -> invoke(f.gateway(), f.claim(), f.validation()))
                .isInstanceOf(DataAccessException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_v2_validation", Integer.class))
                .isZero();
        db.execute("DROP TRIGGER fail_format_grant ON agent_graph_correction");
        assertThatThrownBy(() -> invoke(f.gateway(), f.claim(), f.validation()))
                .hasMessage("FORMAT_REPAIR_REQUIRED");
        assertThat(f.calls()).hasValue(2);
        db.update("UPDATE agent_tool_tenant_grant SET enabled=false");
        assertThatThrownBy(() -> invoke(f.gateway(), f.claim(), formatRepairRequest(f)))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(f.calls()).hasValue(2);
        assertThatThrownBy(() -> db.update("DELETE FROM agent_graph_format_policy"))
                .isInstanceOf(DataAccessException.class);
    }

    private static final String MEMORY =
            CHECKLIST.replace(
                    "\"type\":\"object\"",
                    "\"type\":\"object\",\"x-graph-memory\":{\"version\":1,\"inline_characters\":256,\"summary_characters\":512}");

    private record MemoryFixture(
            GraphCognitionGateway gateway,
            Claim claim,
            java.util.ArrayList<String> refs,
            Receipt tool,
            AtomicInteger calls) {}

    private MemoryFixture memoryFixture(
            AtomicInteger calls,
            IntFunction<ModelProviderResponse> responses,
            java.util.function.Function<AgentTool.Request, String> output,
            int models) {
        var gateway = cognition(calls, responses, MEMORY, 6000, output);
        executions.reserve("alpha", 1, "b".repeat(64), models, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var model =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL", "model:first", List.of(claim.binding().inputRef()), null));
        var intent = model.decision().tools().getFirst();
        var tool =
                invoke(
                        gateway,
                        claim,
                        new Invocation("TOOL", intent.argumentsRef(), List.of(), intent));
        return new MemoryFixture(
                gateway,
                claim,
                new java.util.ArrayList<>(
                        List.of(claim.binding().inputRef(), model.receiptRef(), tool.receiptRef())),
                tool,
                calls);
    }

    private String largeMemoryResult(String label, boolean approved) {
        return "{\"word\":\"hi\",\"approved\":"
                + approved
                + ",\"amount\":20000.000000000001,\"detail\":\""
                + label
                + "_"
                + "x".repeat(12000)
                + "_TAIL_ONLY\"}";
    }

    private Invocation summaryRequest(Claim claim, List<String> refs, String previous) {
        var inputs = new java.util.ArrayList<>(refs);
        if (previous != null) {
            inputs.add(previous);
        }
        return new Invocation(
                "MODEL",
                "summary:" + codec.fingerprint(List.of(codec.fingerprint(claim.binding()), inputs)),
                List.copyOf(inputs),
                null);
    }

    @Test
    void memoryCompressionAndReadKeepOriginalFactsAndExactNumbers() {
        var source = new java.util.concurrent.atomic.AtomicReference<String>();
        var calls = new AtomicInteger();
        var f =
                memoryFixture(
                        calls,
                        n ->
                                n == 1
                                        ? toolResponse()
                                        : answer(
                                                n == 2
                                                        ? "All approved (untrusted summary)"
                                                        : n == 3
                                                                ? "{\"action\":\"READ_RESULT\",\"reference\":\""
                                                                        + source.get()
                                                                        + "\",\"pointer\":\"/amount\"}"
                                                                : "{\"approved\":true}"),
                        r -> largeMemoryResult("PRIVATE", false),
                        8);
        source.set(f.tool().receiptRef());
        var capacity = trackedCapacity();
        var governed =
                new GraphCapacityGateway(
                        f.gateway(), executions, capacity, r -> List.of("account:physical"), codec);
        var blocked = new Invocation("MODEL", "model:resume", List.copyOf(f.refs()), null);
        assertThatThrownBy(
                        () ->
                                governed.invoke(
                                        f.claim().capability(),
                                        new InvokeRequest(
                                                f.claim().binding(),
                                                f.claim().generation(),
                                                blocked)))
                .hasMessage("CONTEXT_REQUIRED");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_call_permit", Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_budget_operation WHERE"
                                        + " kind='MODEL'",
                                Integer.class))
                .isEqualTo(1);
        var summary = invoke(f.gateway(), f.claim(), summaryRequest(f.claim(), f.refs(), null));
        f.refs().add(summary.receiptRef());
        assertThatThrownBy(
                        () ->
                                invoke(
                                        f.gateway(),
                                        f.claim(),
                                        new Invocation(
                                                "VALIDATE",
                                                "validate:summary",
                                                List.of(
                                                        summary.decision().candidateRef(),
                                                        summary.receiptRef()),
                                                null)))
                .isInstanceOf(GraphGatewayException.class);
        var context = new java.util.ArrayList<>(f.refs());
        context.add(summary.decision().candidateRef());
        var readModel =
                governed.invoke(
                                f.claim().capability(),
                                new InvokeRequest(
                                        f.claim().binding(),
                                        f.claim().generation(),
                                        new Invocation(
                                                "MODEL",
                                                blocked.operationId(),
                                                List.copyOf(context),
                                                null)))
                        .receipt();
        assertThat(lastModelInput.get()).contains("All approved").doesNotContain("TAIL_ONLY");
        f.refs().add(readModel.receiptRef());
        var intent = readModel.decision().tools().getFirst();
        context = new java.util.ArrayList<>(f.refs());
        context.add(summary.decision().candidateRef());
        var readRequest =
                new Invocation(
                        "READ_RESULT",
                        "read:" + intent.argumentsRef(),
                        List.copyOf(context),
                        intent);
        var read = invoke(f.gateway(), f.claim(), readRequest);
        f.refs().add(read.receiptRef());
        assertThat(invoke(f.gateway(), f.claim(), readRequest)).isEqualTo(read);
        context = new java.util.ArrayList<>(f.refs());
        context.add(summary.decision().candidateRef());
        context.add(read.decision().candidateRef());
        var answer =
                invoke(
                        f.gateway(),
                        f.claim(),
                        new Invocation("MODEL", "model:final-memory", List.copyOf(context), null));
        assertThat(lastModelInput.get()).contains("20000.000000000001").doesNotContain("TAIL_ONLY");
        assertThat(invoke(f.gateway(), f.claim(), readRequest))
                .isEqualTo(read); // Replay keeps the original causal frontier.
        f.refs().add(answer.receiptRef());
        var validation = new java.util.ArrayList<>(f.refs());
        validation.set(0, answer.decision().candidateRef());
        assertThat(
                        invoke(
                                        f.gateway(),
                                        f.claim(),
                                        new Invocation(
                                                "VALIDATE",
                                                "validate:memory-final",
                                                List.copyOf(validation),
                                                null))
                                .accepted())
                .isFalse();
        assertThat(calls).hasValue(4);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_budget_call WHERE operation_id=?",
                                Integer.class,
                                readRequest.operationId()))
                .isZero();
        assertThat(db.queryForObject("SELECT content FROM agent_graph_read_result", String.class))
                .isEqualTo("20000.000000000001");
    }

    @Test
    void memorySecondSummaryUsesOnlyPreviousNotesAndNewDelta() {
        var outputs = new AtomicInteger();
        var calls = new AtomicInteger();
        var f =
                memoryFixture(
                        calls,
                        n ->
                                n == 1 || n == 3
                                        ? toolResponse()
                                        : answer(n == 2 ? "first notes" : "second notes"),
                        r ->
                                largeMemoryResult(
                                        outputs.incrementAndGet() == 1
                                                ? "FIRST_HEADER"
                                                : "SECOND_HEADER",
                                        true),
                        8);
        var first = invoke(f.gateway(), f.claim(), summaryRequest(f.claim(), f.refs(), null));
        f.refs().add(first.receiptRef());
        var context = new java.util.ArrayList<>(f.refs());
        context.add(first.decision().candidateRef());
        var next =
                invoke(
                        f.gateway(),
                        f.claim(),
                        new Invocation("MODEL", "model:second-tool", List.copyOf(context), null));
        f.refs().add(next.receiptRef());
        var intent = next.decision().tools().getFirst();
        f.refs()
                .add(
                        invoke(
                                        f.gateway(),
                                        f.claim(),
                                        new Invocation(
                                                "TOOL", intent.argumentsRef(), List.of(), intent))
                                .receiptRef());
        var second =
                invoke(
                        f.gateway(),
                        f.claim(),
                        summaryRequest(f.claim(), f.refs(), first.decision().candidateRef()));
        assertThat(lastModelInput.get())
                .contains("first notes", "SECOND_HEADER")
                .doesNotContain("FIRST_HEADER", "TAIL_ONLY");
        assertThat(
                        db.queryForObject(
                                "SELECT parent_ref FROM agent_graph_memory WHERE reference=?",
                                String.class,
                                second.decision().candidateRef()))
                .isEqualTo(first.decision().candidateRef());
        assertThat(
                        db.queryForObject(
                                "SELECT jsonb_array_length(sources_json::jsonb) FROM"
                                        + " agent_graph_memory WHERE reference=?",
                                Integer.class,
                                second.decision().candidateRef()))
                .isEqualTo(2);
        assertThat(calls).hasValue(4);
    }

    @Test
    void memorySummaryPublicationRollbackReplaysRawResponseAndRechecksRevocation() {
        var calls = new AtomicInteger();
        var f =
                memoryFixture(
                        calls,
                        n -> n == 1 ? toolResponse() : answer("saved notes"),
                        r -> largeMemoryResult("PRIVATE", true),
                        8);
        var request = summaryRequest(f.claim(), f.refs(), null);
        db.execute(
                "CREATE FUNCTION fail_memory() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE"
                        + " EXCEPTION ''injected memory failure''; END'");
        db.execute(
                "CREATE TRIGGER fail_memory BEFORE INSERT ON agent_graph_memory FOR EACH ROW"
                        + " EXECUTE FUNCTION fail_memory()");
        assertThatThrownBy(() -> invoke(f.gateway(), f.claim(), request))
                .isInstanceOf(DataAccessException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_memory", Integer.class))
                .isZero();
        assertThat(calls).hasValue(2);
        db.execute("DROP TRIGGER fail_memory ON agent_graph_memory");
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1"
                        + " second'");
        var successor = executions.claim("alpha", "next", 60).orElseThrow();
        var summary = invoke(f.gateway(), successor, request);
        assertThat(invoke(f.gateway(), successor, request)).isEqualTo(summary);
        assertThat(calls).hasValue(2);
        assertThatThrownBy(() -> db.update("DELETE FROM agent_graph_memory"))
                .isInstanceOf(DataAccessException.class);
        db.update("UPDATE agent_tool_tenant_grant SET enabled=false");
        assertThatThrownBy(() -> invoke(f.gateway(), successor, request))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void memoryReadIntentCompletesCorrectionAtomically() {
        var calls = new AtomicInteger();
        var source = new java.util.concurrent.atomic.AtomicReference<String>();
        var gateway =
                cognition(
                        calls,
                        n ->
                                n <= 3
                                        ? toolResponse()
                                        : answer(
                                                n == 4
                                                        ? "{\"action\":\"READ_RESULT\",\"reference\":\""
                                                                + source.get()
                                                                + "\",\"pointer\":\"/approved\"}"
                                                        : "{\"approved\":true}"),
                        MEMORY,
                        40000,
                        r -> "{\"word\":\"hi\",\"approved\":true}");
        executions.reserve("alpha", 1, "b".repeat(64), 10, 10, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new java.util.ArrayList<String>();
        refs.add(claim.binding().inputRef());
        Invocation trigger = null;
        for (int i = 0; i < 3; i++) {
            var model =
                    invoke(
                            gateway,
                            claim,
                            new Invocation("MODEL", "model:" + i, List.copyOf(refs), null));
            refs.add(model.receiptRef());
            var intent = model.decision().tools().getFirst();
            var tool = new Invocation("TOOL", intent.argumentsRef(), List.of(), intent);
            if (i < 2) {
                var result = invoke(gateway, claim, tool);
                refs.add(result.receiptRef());
                source.set(result.receiptRef());
            } else {
                trigger = tool;
            }
        }
        var blocked = trigger;
        assertThatThrownBy(() -> invoke(gateway, claim, blocked)).hasMessage("CORRECTION_REQUIRED");
        var correction =
                new Invocation(
                        "MODEL",
                        "correction:"
                                + codec.fingerprint(
                                        List.of(
                                                codec.fingerprint(claim.binding()),
                                                codec.fingerprint(trigger))),
                        List.copyOf(refs),
                        null);
        db.execute(
                "CREATE FUNCTION fail_memory_correction() RETURNS trigger LANGUAGE plpgsql AS"
                        + " 'BEGIN RAISE EXCEPTION ''injected''; END'");
        db.execute(
                "CREATE TRIGGER fail_memory_correction BEFORE INSERT ON"
                        + " agent_graph_correction_result FOR EACH ROW EXECUTE FUNCTION"
                        + " fail_memory_correction()");
        assertThatThrownBy(() -> invoke(gateway, claim, correction))
                .isInstanceOf(DataAccessException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_read_intent", Integer.class))
                .isZero();
        db.execute("DROP TRIGGER fail_memory_correction ON agent_graph_correction_result");
        var decision = invoke(gateway, claim, correction);
        assertThat(invoke(gateway, claim, correction)).isEqualTo(decision);
        assertThat(calls).hasValue(4);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_correction_result",
                                Integer.class))
                .isEqualTo(1);
        refs.add(decision.receiptRef());
        var intent = decision.decision().tools().getFirst();
        var read =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "READ_RESULT",
                                "read:" + intent.argumentsRef(),
                                List.copyOf(refs),
                                intent));
        refs.add(read.receiptRef());
        refs.add(read.decision().candidateRef());
        assertThat(
                        invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "MODEL",
                                                "model:after-read",
                                                List.copyOf(refs),
                                                null))
                                .decision()
                                .candidateRef())
                .isNotNull();
        assertThat(calls).hasValue(5);
    }

    @Test
    void memorySummarySharesRunModelBudgetAndCannotHideMissingEvidence() {
        var calls = new AtomicInteger();
        var f =
                memoryFixture(
                        calls,
                        n -> n == 1 ? toolResponse() : answer("notes"),
                        r -> largeMemoryResult("ORIGINAL", true),
                        2);
        var summary = invoke(f.gateway(), f.claim(), summaryRequest(f.claim(), f.refs(), null));
        f.refs().add(summary.receiptRef());
        assertThatThrownBy(
                        () ->
                                invoke(
                                        f.gateway(),
                                        f.claim(),
                                        new Invocation(
                                                "MODEL",
                                                "model:omit",
                                                List.of(
                                                        f.claim().binding().inputRef(),
                                                        summary.receiptRef(),
                                                        summary.decision().candidateRef()),
                                                null)))
                .hasMessage("REFERENCE_DENIED");
        var context = new java.util.ArrayList<>(f.refs());
        context.add(summary.decision().candidateRef());
        assertThatThrownBy(
                        () ->
                                invoke(
                                        f.gateway(),
                                        f.claim(),
                                        new Invocation(
                                                "MODEL",
                                                "model:exhausted",
                                                List.copyOf(context),
                                                null)))
                .hasMessage("RUN_STOPPED");
        assertThat(calls).hasValue(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_budget_call WHERE operation_id"
                                        + " LIKE 'summary:%'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_run_stop", Integer.class))
                .isEqualTo(1);
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(calls).hasValue(2);
    }

    @Test
    void memoryReadFailuresStayLocalBoundedAndReauthorizeCachedArtifacts() throws Exception {
        var source = new java.util.concurrent.atomic.AtomicReference<String>();
        var calls = new AtomicInteger();
        var f =
                memoryFixture(
                        calls,
                        n ->
                                n == 1
                                        ? toolResponse()
                                        : answer(
                                                n == 2
                                                        ? "notes"
                                                        : "{\"action\":\"READ_RESULT\",\"reference\":\""
                                                                + source.get()
                                                                + "\",\"pointer\":\""
                                                                + (n == 3 ? "" : "/missing")
                                                                + "\"}"),
                        r -> largeMemoryResult("PRIVATE", true),
                        8);
        source.set(f.tool().receiptRef());
        var summary = invoke(f.gateway(), f.claim(), summaryRequest(f.claim(), f.refs(), null));
        f.refs().add(summary.receiptRef());
        Invocation lastRead = null;
        String readRef = null;
        for (int i = 0; i < 2; i++) {
            var inputs = new java.util.ArrayList<>(f.refs());
            inputs.add(summary.decision().candidateRef());
            if (readRef != null) {
                inputs.add(readRef);
            }
            var model =
                    invoke(
                            f.gateway(),
                            f.claim(),
                            new Invocation("MODEL", "model:read:" + i, List.copyOf(inputs), null));
            f.refs().add(model.receiptRef());
            inputs = new java.util.ArrayList<>(f.refs());
            inputs.add(summary.decision().candidateRef());
            if (readRef != null) {
                inputs.add(readRef);
            }
            var intent = model.decision().tools().getFirst();
            lastRead =
                    new Invocation(
                            "READ_RESULT",
                            "read:" + intent.argumentsRef(),
                            List.copyOf(inputs),
                            intent);
            var result = invoke(f.gateway(), f.claim(), lastRead);
            f.refs().add(result.receiptRef());
            readRef = result.decision().candidateRef();
            assertThat(
                            db.queryForObject(
                                    "SELECT status FROM agent_graph_read_result WHERE reference=?",
                                    String.class,
                                    readRef))
                    .isEqualTo(i == 0 ? "TOO_LARGE" : "MISSING_FIELD");
            assertThat(
                            db.queryForObject(
                                    "SELECT content FROM agent_graph_read_result WHERE reference=?",
                                    String.class,
                                    readRef))
                    .isEmpty();
        }
        assertThat(calls).hasValue(4);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isEqualTo(1);
        var tables =
                List.of(
                        "agent_graph_memory_policy",
                        "agent_graph_memory",
                        "agent_graph_read_intent",
                        "agent_graph_read_result");
        for (var table : tables) {
            assertThatThrownBy(() -> db.update("DELETE FROM " + table))
                    .isInstanceOf(DataAccessException.class);
        }
        String role = "gm_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
        try {
            admin.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
            admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA " + schema + " TO " + role);
            try (var c = DriverManager.getConnection(url, user, password);
                    var stmt = c.createStatement()) {
                stmt.execute("SET ROLE " + role);
                stmt.execute("SET app.system_worker='false'");
                stmt.execute("SET app.tenant_code='beta'");
                for (var table : tables) {
                    try (var rs =
                            stmt.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
                        rs.next();
                        assertThat(rs.getInt(1)).isZero();
                    }
                }
                stmt.execute("SET app.tenant_code='alpha'");
                for (var table : tables) {
                    try (var rs =
                            stmt.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
                        rs.next();
                        assertThat(rs.getInt(1)).isPositive();
                    }
                }
            }
        } finally {
            admin.execute("REVOKE ALL ON ALL TABLES IN SCHEMA " + schema + " FROM " + role);
            admin.execute("REVOKE USAGE ON SCHEMA " + schema + " FROM " + role);
            admin.execute("DROP ROLE " + role);
        }
        var cached = lastRead;
        db.update("UPDATE agent_tool_tenant_grant SET enabled=false");
        assertThatThrownBy(() -> invoke(f.gateway(), f.claim(), cached))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(calls).hasValue(4);
    }

    @Test
    void memoryReferencesCannotCrossRunsOrBecomeBusinessCandidates() {
        var calls = new AtomicInteger();
        var f =
                memoryFixture(
                        calls,
                        n -> n == 1 ? toolResponse() : answer("notes"),
                        r -> largeMemoryResult("PRIVATE", true),
                        8);
        var summary = invoke(f.gateway(), f.claim(), summaryRequest(f.claim(), f.refs(), null));
        executions.reserve("alpha", 2, "b".repeat(64), 8, 8, 2);
        var other = executions.claim("alpha", "other", 60).orElseThrow();
        assertThatThrownBy(
                        () ->
                                invoke(
                                        f.gateway(),
                                        other,
                                        new Invocation(
                                                "MODEL",
                                                "model:foreign-memory",
                                                List.of(
                                                        other.binding().inputRef(),
                                                        summary.decision().candidateRef()),
                                                null)))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(
                        executions.acknowledge(
                                f.claim(),
                                new Observation(
                                        "CANDIDATE_READY",
                                        "cp:fake",
                                        summary.decision().candidateRef(),
                                        summary.receiptRef())))
                .isTrue();
        var completionStore =
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCompletionStore(named, executions, codec);
        assertThat(
                        completionStore.completeNext(
                                e -> {
                                    throw new AssertionError("summary is not business evidence");
                                },
                                (r, status) -> {
                                    throw new AssertionError();
                                }))
                .isTrue();
        assertThat(
                        db.queryForObject(
                                "SELECT completion_reason FROM agent_graph_dispatch WHERE run_id=1",
                                String.class))
                .isEqualTo("GRAPH_RECEIPT_INVALID");
        assertThat(calls).hasValue(2);
    }

    private record ReplanFixture(
            GraphCognitionGateway gateway,
            Claim claim,
            Invocation trigger,
            Invocation proposal,
            Invocation validation,
            java.util.ArrayList<String> refs,
            AtomicInteger calls) {}

    private ReplanFixture replanFixture(boolean unchanged) {
        return replanFixture(unchanged, 16);
    }

    private ReplanFixture replanFixture(boolean unchanged, int modelLimit) {
        String schema =
                PLANNED.replace(
                                "\"version\":1,\"mode\":\"PLANNED\"",
                                "\"version\":2,\"mode\":\"PLANNED\"")
                        .replace(
                                "\"expected\":true}",
                                "\"expected\":true},{\"id\":\"budget\",\"tool\":\"lookup\",\"value_field\":\"budgeted\",\"expected\":true}");
        var steps =
                List.of(
                        new GraphPlan.Step(
                                "s1",
                                List.of("approval"),
                                List.of("lookup"),
                                List.of(),
                                List.of("approval"),
                                "REJECT"),
                        new GraphPlan.Step(
                                "s2",
                                List.of("budget"),
                                List.of("lookup"),
                                List.of(),
                                List.of("budget"),
                                "REJECT"));
        var initial =
                new GraphPlan(
                        "p1",
                        1,
                        0,
                        codec.fingerprint(GraphTaskContract.parseSchema(schema, codec)),
                        List.of("hi"),
                        "Check both",
                        steps);
        var evidence = new java.util.ArrayList<String>();
        var calls = new AtomicInteger();
        var revisedSteps =
                unchanged
                        ? steps
                        : List.of(
                                new GraphPlan.Step(
                                        "s1",
                                        List.of("approval"),
                                        List.of("lookup"),
                                        List.of("s2"),
                                        List.of("approval"),
                                        "REJECT"),
                                steps.get(1));
        var gateway =
                cognition(
                        calls,
                        n ->
                                answer(
                                        n == 1
                                                ? new String(
                                                        codec.write(initial),
                                                        java.nio.charset.StandardCharsets.UTF_8)
                                                : n == 5
                                                        ? new String(
                                                                codec.write(
                                                                        new GraphPlan(
                                                                                "p1",
                                                                                2,
                                                                                1,
                                                                                initial
                                                                                        .taskContractRef(),
                                                                                List.of("hi"),
                                                                                "Check budget"
                                                                                        + " first",
                                                                                revisedSteps,
                                                                                "Repeated approval"
                                                                                    + " added no"
                                                                                    + " budget"
                                                                                    + " fact",
                                                                                List.copyOf(
                                                                                        evidence))),
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)
                                                        : n > 5
                                                                ? plannedTool()
                                                                        .replace(
                                                                                "\"plan_revision\":1",
                                                                                "\"plan_revision\":2")
                                                                        .replace("s1", "s2")
                                                                : plannedTool()),
                        schema,
                        40000,
                        r -> "{\"word\":\"hi\",\"approved\":true}");
        executions.reserve("alpha", 1, "b".repeat(64), modelLimit, 16, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var initialReceipt =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "plan:" + codec.fingerprint(claim.binding()),
                                List.of(claim.binding().inputRef()),
                                null));
        var candidate = initialReceipt.decision().candidateRef();
        assertThat(
                        invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "VALIDATE",
                                                "plan-validate:"
                                                        + codec.fingerprint(
                                                                List.of(
                                                                        codec.fingerprint(
                                                                                claim.binding()),
                                                                        candidate)),
                                                List.of(candidate, initialReceipt.receiptRef()),
                                                null))
                                .accepted())
                .isTrue();
        var refs =
                new java.util.ArrayList<String>(
                        List.of(claim.binding().inputRef(), initialReceipt.receiptRef()));
        Invocation trigger = null;
        for (int i = 0; i < 3; i++) {
            var model =
                    invoke(
                            gateway,
                            claim,
                            new Invocation(
                                    "MODEL", "model:revision:" + i, List.copyOf(refs), null));
            refs.add(model.receiptRef());
            var intent = model.decision().tools().getFirst();
            trigger = new Invocation("TOOL", intent.argumentsRef(), List.of(), intent);
            if (i < 2) {
                var receipt = invoke(gateway, claim, trigger);
                refs.add(receipt.receiptRef());
                evidence.add(receipt.receiptRef());
            }
        }
        var blocked = trigger;
        assertThatThrownBy(() -> invoke(gateway, claim, blocked)).hasMessage("REPLAN_REQUIRED");
        var proposal =
                new Invocation(
                        "MODEL",
                        "plan:correction:"
                                + codec.fingerprint(
                                        List.of(
                                                codec.fingerprint(claim.binding()),
                                                codec.fingerprint(trigger))),
                        List.copyOf(refs),
                        null);
        var result = invoke(gateway, claim, proposal);
        refs.add(result.receiptRef());
        candidate = result.decision().candidateRef();
        assertThat(lastModelPrompt.get())
                .contains("revision=2, parent_revision=1")
                .doesNotContain("revision=1, parent_revision=0");
        var inputs = new java.util.ArrayList<>(refs);
        inputs.set(0, candidate);
        var validation =
                new Invocation(
                        "VALIDATE",
                        "plan-validate:"
                                + codec.fingerprint(
                                        List.of(codec.fingerprint(claim.binding()), candidate)),
                        List.copyOf(inputs),
                        null);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_correction_result",
                                Integer.class))
                .isZero();
        return new ReplanFixture(gateway, claim, trigger, proposal, validation, refs, calls);
    }

    @Test
    void replanSwitchIsAtomicRecoverableAndCannotResetLoopBudget() {
        var f = replanFixture(false);
        db.execute(
                "CREATE FUNCTION fail_revision() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE"
                        + " EXCEPTION ''injected switch failure''; END'");
        db.execute(
                "CREATE TRIGGER fail_revision BEFORE INSERT ON agent_graph_correction_result FOR"
                        + " EACH ROW EXECUTE FUNCTION fail_revision()");
        assertThatThrownBy(() -> invoke(f.gateway(), f.claim(), f.validation()))
                .isInstanceOf(DataAccessException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_plan_verdict", Integer.class))
                .isEqualTo(1);
        db.execute("DROP TRIGGER fail_revision ON agent_graph_correction_result");
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1"
                        + " second'");
        var next = executions.claim("alpha", "successor", 60).orElseThrow();
        var accepted = invoke(f.gateway(), next, f.validation());
        assertThat(accepted.accepted()).isTrue();
        assertThat(invoke(f.gateway(), next, f.validation())).isEqualTo(accepted);
        assertThat(invoke(f.gateway(), next, f.proposal()).decision().candidateRef())
                .isEqualTo(f.validation().inputRefs().getFirst());
        assertThatThrownBy(() -> invoke(f.gateway(), next, f.trigger()))
                .hasMessage("REPLAN_REQUIRED");
        assertThat(f.calls()).hasValue(5);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_correction", Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_plan_verdict WHERE accepted",
                                Integer.class))
                .isEqualTo(2);
        var action =
                invoke(
                        f.gateway(),
                        next,
                        new Invocation(
                                "MODEL", "model:after-revision", List.copyOf(f.refs()), null));
        var intent = action.decision().tools().getFirst();
        assertThatThrownBy(
                        () ->
                                invoke(
                                        f.gateway(),
                                        next,
                                        new Invocation(
                                                "TOOL", intent.argumentsRef(), List.of(), intent)))
                .hasMessage("RUN_STOPPED");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_correction", Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_budget_call", Integer.class))
                .isEqualTo(8);
    }

    @Test
    void replanConcurrentAcceptanceKeepsOneRevisionAndFencesStaleTools() throws Exception {
        var f = replanFixture(false);
        assertThatThrownBy(
                        () ->
                                invoke(
                                        f.gateway(),
                                        f.claim(),
                                        new Invocation(
                                                "MODEL",
                                                "model:premature",
                                                List.copyOf(f.refs()),
                                                null)))
                .hasMessage("CORRECTION_PENDING");
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                gate.await();
                                return invoke(f.gateway(), f.claim(), f.validation());
                            });
            var b =
                    pool.submit(
                            () -> {
                                gate.await();
                                return invoke(f.gateway(), f.claim(), f.validation());
                            });
            gate.countDown();
            assertThat(a.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isEqualTo(b.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_plan_verdict WHERE accepted",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_correction_result",
                                Integer.class))
                .isEqualTo(1);
        var old =
                codec.read(
                                db.queryForObject(
                                                "SELECT receipt_json FROM agent_graph_v2_model_call"
                                                        + " WHERE ordinal=2",
                                                String.class)
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                Receipt.class)
                        .decision()
                        .tools()
                        .getFirst();
        var oldRequest = new Invocation("TOOL", old.argumentsRef(), List.of(), old);
        // Existing evidence remains readable after current authorization; it never sends again.
        assertThat(invoke(f.gateway(), f.claim(), oldRequest).receiptRef()).isNotNull();
        var run =
                executions.authorize(
                        f.claim().capability(), f.claim().binding(), f.claim().generation());
        var capacity = trackedCapacity();
        var token =
                capacity.acquireTracked(run, oldRequest, () -> List.of("account:physical"))
                        .orElseThrow();
        String oldBudget =
                db.queryForObject(
                        "SELECT call_attempt_id::text FROM agent_graph_budget_call WHERE"
                                + " operation_id=?",
                        String.class,
                        old.argumentsRef());
        assertThatThrownBy(() -> capacity.dispatching(token, run, oldRequest, oldBudget))
                .hasMessage("PLAN_DENIED");
        capacity.finished(token);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isEqualTo(2);
        assertThat(f.calls()).hasValue(5);
    }

    @Test
    void replanNoOpIsRejectedAndClosesWithoutAnotherModel() {
        var f = replanFixture(true);
        var rejected = invoke(f.gateway(), f.claim(), f.validation());
        assertThat(rejected.accepted()).isFalse();
        assertThat(
                        executions.acknowledge(
                                f.claim(),
                                new Observation(
                                        "REJECTED",
                                        "cp:revision",
                                        f.validation().inputRefs().getFirst(),
                                        rejected.receiptRef())))
                .isTrue();
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("FAILED");
        assertThat(f.calls()).hasValue(5);
    }

    @Test
    void replanCannotSpendBeyondRemainingBudgetAndClosesWithoutModel() {
        var f = replanFixture(false, 5);
        var rejected = invoke(f.gateway(), f.claim(), f.validation());
        assertThat(rejected.accepted()).isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT reason_code FROM agent_graph_v2_validation WHERE"
                                        + " operation_id=?",
                                String.class,
                                f.validation().operationId()))
                .isEqualTo("GRAPH_PLAN_BUDGET_INSUFFICIENT");
        assertThat(
                        executions.acknowledge(
                                f.claim(),
                                new Observation(
                                        "REJECTED",
                                        "cp:budget-revision",
                                        f.validation().inputRefs().getFirst(),
                                        rejected.receiptRef())))
                .isTrue();
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(f.calls()).hasValue(5);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_plan_verdict WHERE accepted",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void replanCannotSwitchWhileOldPhysicalCallRemainsUnknown() {
        var f = replanFixture(false);
        var capacity = trackedCapacity();
        var run =
                executions.authorize(
                        f.claim().capability(), f.claim().binding(), f.claim().generation());
        var token =
                capacity.acquire(
                                run,
                                new Invocation(
                                        "MODEL",
                                        "model:old-unknown",
                                        List.of(f.claim().binding().inputRef()),
                                        null),
                                List.of("account:physical"))
                        .orElseThrow();
        capacity.uncertain(token);
        assertThatThrownBy(() -> invoke(f.gateway(), f.claim(), f.validation()))
                .hasMessage("PLAN_SWITCH_PENDING");
        var governed =
                new GraphCapacityGateway(
                        f.gateway(), executions, capacity, r -> List.of("account:physical"), codec);
        var request =
                new InvokeRequest(f.claim().binding(), f.claim().generation(), f.validation());
        var waiting = governed.invoke(f.claim().capability(), request).deferred();
        assertThat(waiting.reason()).isEqualTo("RETRY");
        assertThat(governed.invoke(f.claim().capability(), request).deferred().waitId())
                .isEqualTo(waiting.waitId());
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_plan_verdict", Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_correction_result",
                                Integer.class))
                .isZero();
        capacity.reconcile(token, "trusted-test-proof");
        assertThat(invoke(f.gateway(), f.claim(), f.validation()).accepted()).isTrue();
        assertThat(f.calls()).hasValue(5);
    }

    @Test
    void plannedRunAcceptsOnceAndBindsToolThenCompletes() throws Exception {
        var calls = new AtomicInteger();
        var gateway =
                cognition(
                        calls,
                        n ->
                                answer(
                                        n == 1
                                                ? planBody()
                                                : n == 2 ? plannedTool() : "{\"approved\":true}"),
                        PLANNED,
                        40000,
                        r -> "{\"word\":\"hi\",\"approved\":true}");
        executions.reserve("alpha", 1, "b".repeat(64), 8, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "MODEL",
                                                "model:early",
                                                List.of(claim.binding().inputRef()),
                                                null)))
                .hasMessage("PLAN_REQUIRED");
        assertThat(calls.get()).isZero();
        var planRequest =
                new Invocation(
                        "MODEL",
                        "plan:" + codec.fingerprint(claim.binding()),
                        List.of(claim.binding().inputRef()),
                        null);
        var proposal = invoke(gateway, claim, planRequest);
        assertThat(invoke(gateway, claim, planRequest)).isEqualTo(proposal);
        String candidate = proposal.decision().candidateRef();
        var validationRequest =
                new Invocation(
                        "VALIDATE",
                        "plan-validate:"
                                + codec.fingerprint(
                                        List.of(codec.fingerprint(claim.binding()), candidate)),
                        List.of(candidate, proposal.receiptRef()),
                        null);
        db.execute(
                "CREATE FUNCTION fail_plan_verdict() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN"
                        + " RAISE EXCEPTION ''injected plan publication failure''; END'");
        db.execute(
                "CREATE TRIGGER fail_plan_verdict BEFORE INSERT ON agent_graph_plan_verdict FOR"
                        + " EACH ROW EXECUTE FUNCTION fail_plan_verdict()");
        assertThatThrownBy(() -> invoke(gateway, claim, validationRequest))
                .isInstanceOf(DataAccessException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_v2_validation", Integer.class))
                .isZero();
        db.execute("DROP TRIGGER fail_plan_verdict ON agent_graph_plan_verdict");
        var acceptance = invoke(gateway, claim, validationRequest);
        assertThat(acceptance.accepted()).isTrue();
        assertThat(
                        executions.acknowledge(
                                claim,
                                new Observation(
                                        "CANDIDATE_READY",
                                        "cp:fake",
                                        candidate,
                                        acceptance.receiptRef())))
                .isFalse();
        reconnectStores();
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1"
                        + " second'");
        var successor = executions.claim("alpha", "successor", 60).orElseThrow();
        assertThat(successor.generation()).isGreaterThan(claim.generation());
        assertThat(invoke(gateway, successor, validationRequest)).isEqualTo(acceptance);
        assertThat(calls.get()).isEqualTo(1);
        var model =
                invoke(
                        gateway,
                        successor,
                        new Invocation(
                                "MODEL",
                                "model:action",
                                List.of(successor.binding().inputRef(), proposal.receiptRef()),
                                null));
        var intent = model.decision().tools().getFirst();
        assertThat(
                        db.queryForObject(
                                "SELECT step_id FROM agent_graph_plan_intent WHERE intent_ref=?",
                                String.class,
                                intent.argumentsRef()))
                .isEqualTo("s1");
        var tool =
                invoke(
                        gateway,
                        successor,
                        new Invocation("TOOL", intent.argumentsRef(), List.of(), intent));
        var answer =
                invoke(
                        gateway,
                        successor,
                        new Invocation(
                                "MODEL",
                                "model:final",
                                List.of(
                                        successor.binding().inputRef(),
                                        proposal.receiptRef(),
                                        model.receiptRef(),
                                        tool.receiptRef()),
                                null));
        var validation =
                invoke(
                        gateway,
                        successor,
                        new Invocation(
                                "VALIDATE",
                                "validate:final",
                                List.of(
                                        answer.decision().candidateRef(),
                                        proposal.receiptRef(),
                                        model.receiptRef(),
                                        tool.receiptRef(),
                                        answer.receiptRef()),
                                null));
        assertThat(validation.accepted()).isTrue();
        assertThat(
                        executions.acknowledge(
                                successor,
                                new Observation(
                                        "CANDIDATE_READY",
                                        "cp:plan",
                                        answer.decision().candidateRef(),
                                        validation.receiptRef())))
                .isTrue();
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("SUCCEEDED");
        assertThat(calls.get()).isEqualTo(3);
        assertThatThrownBy(() -> db.update("DELETE FROM agent_graph_plan_verdict"))
                .isInstanceOf(DataAccessException.class);
        var tables =
                List.of(
                        "agent_graph_plan_policy",
                        "agent_graph_plan_verdict",
                        "agent_graph_plan_intent");
        for (var table : tables) {
            assertThatThrownBy(() -> db.update("DELETE FROM " + table))
                    .isInstanceOf(DataAccessException.class);
        }
        String role = "gc_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
        try {
            admin.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
            admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA " + schema + " TO " + role);
            try (var c = DriverManager.getConnection(url, user, password);
                    var stmt = c.createStatement()) {
                stmt.execute("SET ROLE " + role);
                stmt.execute("SET app.tenant_code='beta'");
                for (var table : tables) {
                    try (var rs =
                            stmt.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
                        rs.next();
                        assertThat(rs.getInt(1)).isZero();
                    }
                }
                stmt.execute("SET app.tenant_code='alpha'");
                for (var table : tables) {
                    try (var rs =
                            stmt.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
                        rs.next();
                        assertThat(rs.getInt(1)).isEqualTo(1);
                    }
                }
            }
        } finally {
            admin.execute("REVOKE ALL ON ALL TABLES IN SCHEMA " + schema + " FROM " + role);
            admin.execute("REVOKE USAGE ON SCHEMA " + schema + " FROM " + role);
            admin.execute("DROP ROLE " + role);
        }
    }

    @Test
    void rejectedPlanProducesNoToolsAndCanCloseWithoutAnotherModel() {
        var calls = new AtomicInteger();
        var gateway =
                cognition(calls, n -> answer(planBody().replace("hi", "P-18")), PLANNED, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 8, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var proposal =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "plan:" + codec.fingerprint(claim.binding()),
                                List.of(claim.binding().inputRef()),
                                null));
        String candidate = proposal.decision().candidateRef();
        var validation =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "VALIDATE",
                                "plan-validate:"
                                        + codec.fingerprint(
                                                List.of(
                                                        codec.fingerprint(claim.binding()),
                                                        candidate)),
                                List.of(candidate, proposal.receiptRef()),
                                null));
        assertThat(validation.accepted()).isFalse();
        assertThat(
                        executions.acknowledge(
                                claim,
                                new Observation(
                                        "REJECTED", "cp:plan", candidate, validation.receiptRef())))
                .isTrue();
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("FAILED");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isZero();
    }

    @Test
    void planAcceptanceRequiresRemainingExecutionBudget() {
        var gateway = cognition(new AtomicInteger(), n -> answer(planBody()), PLANNED, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 2, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var proposal =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "plan:" + codec.fingerprint(claim.binding()),
                                List.of(claim.binding().inputRef()),
                                null));
        String candidate = proposal.decision().candidateRef();
        var validation =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "VALIDATE",
                                "plan-validate:"
                                        + codec.fingerprint(
                                                List.of(
                                                        codec.fingerprint(claim.binding()),
                                                        candidate)),
                                List.of(candidate, proposal.receiptRef()),
                                null));
        assertThat(validation.accepted()).isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT reason_code FROM agent_graph_v2_validation", String.class))
                .isEqualTo("GRAPH_PLAN_BUDGET_INSUFFICIENT");
    }

    @Test
    void plannedDependenciesUseCommittedFactsAcrossTwoSteps() {
        String schema =
                PLANNED.replace(
                        "\"expected\":true}",
                        "\"expected\":true},{\"id\":\"budget\",\"tool\":\"lookup\",\"value_field\":\"budgeted\",\"expected\":true}");
        var plan =
                new GraphPlan(
                        "p1",
                        1,
                        0,
                        codec.fingerprint(GraphTaskContract.parseSchema(schema, codec)),
                        List.of("hi"),
                        "Check approval and budget",
                        List.of(
                                new GraphPlan.Step(
                                        "s1",
                                        List.of("approval"),
                                        List.of("lookup"),
                                        List.of(),
                                        List.of("approval"),
                                        "REJECT"),
                                new GraphPlan.Step(
                                        "s2",
                                        List.of("budget"),
                                        List.of("lookup"),
                                        List.of("s1"),
                                        List.of("budget"),
                                        "REJECT")));
        var calls = new AtomicInteger();
        var toolCalls = new AtomicInteger();
        var gateway =
                cognition(
                        calls,
                        n ->
                                answer(
                                        n == 1
                                                ? new String(
                                                        codec.write(plan),
                                                        java.nio.charset.StandardCharsets.UTF_8)
                                                : n == 2
                                                        ? plannedTool()
                                                        : n == 3
                                                                ? plannedTool().replace("s1", "s2")
                                                                : "{\"approved\":true}"),
                        schema,
                        40000,
                        r ->
                                toolCalls.incrementAndGet() == 1
                                        ? "{\"word\":\"hi\",\"approved\":true}"
                                        : "{\"word\":\"hi\",\"budgeted\":true}");
        executions.reserve("alpha", 1, "b".repeat(64), 8, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var proposal =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "plan:" + codec.fingerprint(claim.binding()),
                                List.of(claim.binding().inputRef()),
                                null));
        String candidate = proposal.decision().candidateRef();
        assertThat(
                        invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "VALIDATE",
                                                "plan-validate:"
                                                        + codec.fingerprint(
                                                                List.of(
                                                                        codec.fingerprint(
                                                                                claim.binding()),
                                                                        candidate)),
                                                List.of(candidate, proposal.receiptRef()),
                                                null))
                                .accepted())
                .isTrue();
        var refs =
                new java.util.ArrayList<String>(
                        List.of(claim.binding().inputRef(), proposal.receiptRef()));
        for (int i = 0; i < 2; i++) {
            var model =
                    invoke(
                            gateway,
                            claim,
                            new Invocation("MODEL", "model:step:" + i, List.copyOf(refs), null));
            refs.add(model.receiptRef());
            var intent = model.decision().tools().getFirst();
            refs.add(
                    invoke(
                                    gateway,
                                    claim,
                                    new Invocation(
                                            "TOOL", intent.argumentsRef(), List.of(), intent))
                            .receiptRef());
        }
        var answer =
                invoke(
                        gateway,
                        claim,
                        new Invocation("MODEL", "model:final", List.copyOf(refs), null));
        refs.removeFirst();
        refs.add(answer.receiptRef());
        refs.addFirst(answer.decision().candidateRef());
        assertThat(
                        invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "VALIDATE",
                                                "validate:final",
                                                List.copyOf(refs),
                                                null))
                                .accepted())
                .isTrue();
        assertThat(toolCalls.get()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_plan_intent", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void blankOutputSchemaPreservesReactiveV2Compatibility() {
        var gateway = cognition(new AtomicInteger(), n -> answer("ok"), "", 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 2, 0, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        assertThat(
                        invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "MODEL",
                                                "model:normal",
                                                List.of(claim.binding().inputRef()),
                                                null))
                                .decision()
                                .candidateRef())
                .isNotNull();
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_plan_policy", Integer.class))
                .isZero();
    }

    @Test
    void modelCannotExecuteAnUnacceptedPlanStep() {
        var calls = new AtomicInteger();
        var gateway =
                cognition(
                        calls,
                        n -> answer(n == 1 ? planBody() : plannedTool().replace("s1", "invented")),
                        PLANNED,
                        40000);
        executions.reserve("alpha", 1, "b".repeat(64), 8, 8, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var proposal =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "plan:" + codec.fingerprint(claim.binding()),
                                List.of(claim.binding().inputRef()),
                                null));
        String candidate = proposal.decision().candidateRef();
        invoke(
                gateway,
                claim,
                new Invocation(
                        "VALIDATE",
                        "plan-validate:"
                                + codec.fingerprint(
                                        List.of(codec.fingerprint(claim.binding()), candidate)),
                        List.of(candidate, proposal.receiptRef()),
                        null));
        var action =
                new Invocation(
                        "MODEL",
                        "model:illegal",
                        List.of(claim.binding().inputRef(), proposal.receiptRef()),
                        null);
        assertThatThrownBy(() -> invoke(gateway, claim, action)).hasMessage("PLAN_DENIED");
        assertThatThrownBy(() -> invoke(gateway, claim, action)).hasMessage("PLAN_DENIED");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_plan_intent", Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isZero();
    }

    @Test
    void checklistMissingFactsCannotBeReplacedByModelClaims() {
        var gateway =
                cognition(
                        new AtomicInteger(), n -> answer("{\"approved\":true}"), CHECKLIST, 40000);
        executions.reserve("alpha", 1, "b".repeat(64), 5, 5, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var model =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL", "model:claim", List.of(claim.binding().inputRef()), null));
        var validation =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "VALIDATE",
                                "validate:claim",
                                List.of(model.decision().candidateRef(), model.receiptRef()),
                                null));
        assertThat(validation.accepted()).isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT report_json FROM agent_graph_fact_validation",
                                String.class))
                .contains("MISSING");
    }

    @Test
    void checklistNegativeEvidenceIsPersistedAndRejectsCompletion() {
        var gateway =
                cognition(
                        new AtomicInteger(),
                        n -> n == 1 ? toolResponse() : answer("{\"approved\":true}"),
                        CHECKLIST,
                        40000,
                        r -> "{\"word\":\"hi\",\"approved\":false}");
        executions.reserve("alpha", 1, "b".repeat(64), 5, 5, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var first =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL", "model:1", List.of(claim.binding().inputRef()), null));
        var intent = first.decision().tools().getFirst();
        var tool =
                invoke(
                        gateway,
                        claim,
                        new Invocation("TOOL", intent.argumentsRef(), List.of(), intent));
        var candidate =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "model:2",
                                List.of(
                                        claim.binding().inputRef(),
                                        first.receiptRef(),
                                        tool.receiptRef()),
                                null));
        var validation =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "VALIDATE",
                                "validate:negative",
                                List.of(
                                        candidate.decision().candidateRef(),
                                        first.receiptRef(),
                                        tool.receiptRef(),
                                        candidate.receiptRef()),
                                null));
        assertThat(validation.accepted()).isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT report_json FROM agent_graph_fact_validation",
                                String.class))
                .contains("REJECTED");
        assertThat(
                        executions.acknowledge(
                                claim,
                                new Observation(
                                        "REJECTED",
                                        "cp:negative",
                                        candidate.decision().candidateRef(),
                                        validation.receiptRef())))
                .isTrue();
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("FAILED");
    }

    @Test
    void correctionCannotExceedFrozenModelBudget() {
        var gateway =
                cognition(
                        new AtomicInteger(),
                        n -> toolResponse(),
                        CHECKLIST,
                        40000,
                        r -> "{\"word\":\"hi\",\"approved\":true}");
        executions.reserve("alpha", 1, "b".repeat(64), 3, 10, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new java.util.ArrayList<String>();
        refs.add(claim.binding().inputRef());
        for (int i = 0; i < 3; i++) {
            var model =
                    invoke(
                            gateway,
                            claim,
                            new Invocation("MODEL", "model:" + i, List.copyOf(refs), null));
            refs.add(model.receiptRef());
            var intent = model.decision().tools().getFirst();
            var tool = new Invocation("TOOL", intent.argumentsRef(), List.of(), intent);
            if (i < 2) {
                refs.add(invoke(gateway, claim, tool).receiptRef());
            } else {
                assertThatThrownBy(() -> invoke(gateway, claim, tool)).hasMessage("RUN_STOPPED");
            }
        }
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_correction", Integer.class))
                .isZero();
        assertThat(completion(false).completeNext()).isTrue();
    }

    @Test
    void correctionIsDurableSingleUseAndPreservesCausalHistory() throws Exception {
        var calls = new AtomicInteger();
        var gateway =
                cognition(
                        calls,
                        n -> n <= 3 ? toolResponse() : answer("{\"approved\":true}"),
                        CHECKLIST,
                        40000,
                        r -> "{\"word\":\"hi\",\"approved\":true}");
        executions.reserve("alpha", 1, "b".repeat(64), 10, 10, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new java.util.ArrayList<String>();
        refs.add(claim.binding().inputRef());
        Invocation blocked = null;
        for (int i = 0; i < 3; i++) {
            var model =
                    invoke(
                            gateway,
                            claim,
                            new Invocation("MODEL", "model:" + i, List.copyOf(refs), null));
            refs.add(model.receiptRef());
            var intent = model.decision().tools().getFirst();
            var tool = new Invocation("TOOL", intent.argumentsRef(), List.of(), intent);
            if (i < 2) {
                refs.add(invoke(gateway, claim, tool).receiptRef());
            } else {
                blocked = tool;
            }
        }
        var trigger = blocked;
        assertThatThrownBy(() -> invoke(gateway, claim, trigger)).hasMessage("CORRECTION_REQUIRED");
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_run_stop", Integer.class))
                .isZero();
        assertThat(executions.acknowledge(claim, observed())).isFalse();
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "MODEL", "model:bypass", List.copyOf(refs), null)))
                .hasMessage("CORRECTION_PENDING");
        assertThatThrownBy(
                        () ->
                                invoke(
                                        gateway,
                                        claim,
                                        new Invocation(
                                                "MODEL",
                                                "correction:fake",
                                                List.copyOf(refs),
                                                null)))
                .hasMessage("CORRECTION_DENIED");
        reconnectStores();
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1"
                        + " second'");
        var resumed = executions.claim("alpha", "successor", 60).orElseThrow();
        assertThat(resumed.generation()).isGreaterThan(claim.generation());
        assertThatThrownBy(() -> invoke(gateway, resumed, trigger))
                .hasMessage("CORRECTION_REQUIRED");
        String id =
                "correction:"
                        + codec.fingerprint(
                                List.of(
                                        codec.fingerprint(resumed.binding()),
                                        codec.fingerprint(trigger)));
        assertThat(
                        db.queryForObject(
                                "SELECT operation_id FROM agent_graph_correction", String.class))
                .isEqualTo(id);
        var correction = new Invocation("MODEL", id, List.copyOf(refs), null);
        db.execute(
                "CREATE FUNCTION fail_correction_result() RETURNS trigger LANGUAGE plpgsql AS"
                    + " 'BEGIN RAISE EXCEPTION ''injected correction publication failure''; END'");
        db.execute(
                "CREATE TRIGGER fail_correction_result BEFORE INSERT ON"
                        + " agent_graph_correction_result FOR EACH ROW EXECUTE FUNCTION"
                        + " fail_correction_result()");
        assertThatThrownBy(() -> invoke(gateway, resumed, correction))
                .isInstanceOf(DataAccessException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_correction_result",
                                Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_v2_model_call WHERE"
                                        + " operation_id=? AND receipt_json IS NOT NULL",
                                Integer.class,
                                id))
                .isZero();
        db.execute("DROP TRIGGER fail_correction_result ON agent_graph_correction_result");
        var candidate = invoke(gateway, resumed, correction);
        assertThat(invoke(gateway, resumed, correction)).isEqualTo(candidate);
        assertThat(calls.get()).isEqualTo(4);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_correction_result",
                                Integer.class))
                .isEqualTo(1);
        refs.removeFirst();
        refs.add(candidate.receiptRef());
        refs.addFirst(candidate.decision().candidateRef());
        var validation =
                invoke(
                        gateway,
                        resumed,
                        new Invocation("VALIDATE", "validate:fixed", List.copyOf(refs), null));
        assertThat(validation.accepted()).isTrue();
        assertThat(
                        executions.acknowledge(
                                resumed,
                                new Observation(
                                        "CANDIDATE_READY",
                                        "cp:fixed",
                                        candidate.decision().candidateRef(),
                                        validation.receiptRef())))
                .isTrue();
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("SUCCEEDED");
        var tables =
                List.of(
                        "agent_graph_task_contract",
                        "agent_graph_correction",
                        "agent_graph_correction_result",
                        "agent_graph_fact_validation");
        for (var table : tables) {
            assertThatThrownBy(() -> db.update("DELETE FROM " + table))
                    .isInstanceOf(DataAccessException.class);
        }
        String role = "gc_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
        try {
            admin.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
            admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA " + schema + " TO " + role);
            try (var c = DriverManager.getConnection(url, user, password);
                    var stmt = c.createStatement()) {
                stmt.execute("SET ROLE " + role);
                stmt.execute("SET app.tenant_code='beta'");
                for (var table : tables) {
                    try (var rs =
                            stmt.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
                        rs.next();
                        assertThat(rs.getInt(1)).isZero();
                    }
                }
                stmt.execute("SET app.tenant_code='alpha'");
                for (var table : tables) {
                    try (var rs =
                            stmt.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
                        rs.next();
                        assertThat(rs.getInt(1)).isEqualTo(1);
                    }
                }
            }
        } finally {
            admin.execute("REVOKE ALL ON ALL TABLES IN SCHEMA " + schema + " FROM " + role);
            admin.execute("REVOKE USAGE ON SCHEMA " + schema + " FROM " + role);
            admin.execute("DROP ROLE " + role);
        }
    }

    @Test
    void correctionCannotResetLoopOrGrantSecondAttempt() {
        var gateway =
                cognition(
                        new AtomicInteger(),
                        n -> toolResponse(),
                        CHECKLIST,
                        40000,
                        r -> "{\"word\":\"hi\",\"approved\":true}");
        executions.reserve("alpha", 1, "b".repeat(64), 10, 10, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new java.util.ArrayList<String>();
        refs.add(claim.binding().inputRef());
        String modelId = "model:0";
        for (int i = 0; i < 4; i++) {
            var model =
                    invoke(
                            gateway,
                            claim,
                            new Invocation("MODEL", modelId, List.copyOf(refs), null));
            refs.add(model.receiptRef());
            var intent = model.decision().tools().getFirst();
            var tool = new Invocation("TOOL", intent.argumentsRef(), List.of(), intent);
            if (i < 2) {
                refs.add(invoke(gateway, claim, tool).receiptRef());
                modelId = "model:" + (i + 1);
            } else if (i == 2) {
                assertThatThrownBy(() -> invoke(gateway, claim, tool))
                        .hasMessage("CORRECTION_REQUIRED");
                modelId =
                        "correction:"
                                + codec.fingerprint(
                                        List.of(
                                                codec.fingerprint(claim.binding()),
                                                codec.fingerprint(tool)));
            } else {
                assertThatThrownBy(() -> invoke(gateway, claim, tool)).hasMessage("RUN_STOPPED");
            }
        }
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_correction", Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isEqualTo(2);
        assertThat(completion(false).completeNext()).isTrue();
    }

    private record LoopCase(
            GraphCognitionGateway gateway, Claim claim, Invocation proposed, AtomicInteger calls) {}

    private LoopCase loopProposal(
            List<String> completed,
            String next,
            GraphLoopPolicy policy,
            java.util.function.Function<AgentTool.Request, String> outputs) {
        var calls = new AtomicInteger();
        var args = new java.util.ArrayList<>(completed);
        args.add(next);
        var gateway =
                cognition(
                        calls,
                        n ->
                                new ModelProviderResponse(
                                        "",
                                        "fixture",
                                        "request",
                                        "tool_calls",
                                        1,
                                        1,
                                        0,
                                        1,
                                        new ModelProviderResponse.ToolCall(
                                                "lookup", args.get(n - 1), "call")),
                        null,
                        40000,
                        outputs);
        executions.reserve(
                "alpha",
                1,
                "b".repeat(64),
                10,
                20,
                2,
                new io.github.illuseahashmap.agent.runtime.application.port.GraphRunBudgetPort
                        .Policy(30, 30, 10),
                policy);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new java.util.ArrayList<String>();
        refs.add(claim.binding().inputRef());
        for (int i = 0; i < args.size(); i++) {
            var model =
                    invoke(
                            gateway,
                            claim,
                            new Invocation("MODEL", "model:loop:" + i, List.copyOf(refs), null));
            refs.add(model.receiptRef());
            var intent = model.decision().tools().getFirst();
            var request = new Invocation("TOOL", intent.argumentsRef(), List.of(), intent);
            if (i == completed.size()) {
                return new LoopCase(gateway, claim, request, calls);
            }
            var receipt = invoke(gateway, claim, request);
            assertThat(invoke(gateway, claim, request))
                    .isEqualTo(receipt); // Lost ACK/cache must not add an observation.
            refs.add(receipt.receiptRef());
        }
        throw new AssertionError();
    }

    private LoopCase loopProposal(String completed, String next) {
        return loopProposal(
                completed.chars().mapToObj(c -> "{\"word\":\"" + (char) c + "\"}").toList(),
                "{\"word\":\"" + next + "\"}",
                GraphLoopPolicy.defaults(),
                r -> "[]");
    }

    private void assertLoopStopped(LoopCase fixture, String pattern, int completed) {
        assertThatThrownBy(() -> invoke(fixture.gateway(), fixture.claim(), fixture.proposed()))
                .hasMessage("RUN_STOPPED");
        assertThat(db.queryForObject("SELECT reason FROM agent_graph_run_stop", String.class))
                .isEqualTo("LOOP_DETECTED");
        assertThat(
                        db.queryForObject(
                                "SELECT pattern FROM agent_graph_loop_verdict WHERE operation_id=?",
                                String.class,
                                fixture.proposed().operationId()))
                .isEqualTo(pattern);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isEqualTo(completed);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_budget_call c JOIN"
                                    + " agent_graph_budget_operation o"
                                    + " USING(tenant_code,run_id,operation_id) WHERE o.kind='TOOL'",
                                Integer.class))
                .isEqualTo(completed);
        assertThat(completion(false).completeNext()).isTrue();
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class))
                .isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT output FROM completion_test_outbox", String.class))
                .isNull();
    }

    @Test
    void v2LoopAaStopsBeforeThirdToolAndKeepsImmutableEvidence() {
        var fixture =
                loopProposal(
                        List.of(
                                "{\"word\":\"a\",\"amount\":1.00}",
                                "{\"amount\":1,\"word\":\"a\"}"),
                        "{\"word\":\"a\",\"amount\":1}",
                        GraphLoopPolicy.defaults(),
                        r -> "[]");
        assertLoopStopped(fixture, "REPEATED_ACTION_RESULT", 2);
        assertThat(
                        db.queryForObject(
                                "SELECT jsonb_array_length(evidence_json::jsonb->'matched_refs')"
                                        + " FROM agent_graph_loop_verdict WHERE pattern<>'ALLOW'",
                                Integer.class))
                .isEqualTo(2);
        assertThatThrownBy(() -> db.update("UPDATE agent_graph_loop_policy SET policy_version=1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.update("DELETE FROM agent_graph_loop_verdict"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void v2LoopAbabStopsBeforeFifthTool() {
        assertLoopStopped(loopProposal("ABAB", "A"), "SHORT_CYCLE_2", 4);
    }

    @Test
    void v2LoopAbcabcStopsBeforeSeventhTool() {
        assertLoopStopped(loopProposal("ABCABC", "A"), "SHORT_CYCLE_3", 6);
    }

    @Test
    void v2LoopHistorySurvivesFreshGatewayAndNewGeneration() {
        var fixture = loopProposal("AA", "A");
        db.update(
                "UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1"
                        + " second'");
        reconnectStores();
        var claim = executions.claim("alpha", "replacement", 60).orElseThrow();
        var restored =
                cognition(
                        fixture.calls(),
                        n -> {
                            throw new AssertionError("No model required");
                        },
                        null,
                        40000);
        assertLoopStopped(
                new LoopCase(restored, claim, fixture.proposed(), fixture.calls()),
                "REPEATED_ACTION_RESULT",
                2);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_run_attempt", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void v2LoopFrozenTraceDeclarationCannotBeChangedOrEvadedByNewTrace() {
        var policy = new GraphLoopPolicy(Map.of("lookup", List.of("traceId")), Map.of());
        var fixture =
                loopProposal(
                        List.of(
                                "{\"traceId\":\"one\",\"word\":\"a\"}",
                                "{\"word\":\"a\",\"traceId\":\"two\"}"),
                        "{\"traceId\":\"three\",\"word\":\"a\"}",
                        policy,
                        r -> "[]");
        assertThatThrownBy(() -> executions.reserve("alpha", 1, "b".repeat(64), 10, 20, 2))
                .isInstanceOf(GraphGatewayException.class);
        assertLoopStopped(fixture, "REPEATED_ACTION_RESULT", 2);
    }

    @Test
    void v2LoopDifferentPageIsAllowedEvenIfBothPagesAreEmpty() {
        var fixture = loopProposal("AB", "C");
        invoke(fixture.gateway(), fixture.claim(), fixture.proposed());
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_run_stop", Integer.class))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void v2LoopChangedObservableResultsAllowFurtherEvidenceCollection() {
        var output = new AtomicInteger();
        var fixture =
                loopProposal(
                        List.of("{}", "{}"),
                        "{}",
                        GraphLoopPolicy.defaults(),
                        r -> "{\"state\":" + output.incrementAndGet() + "}");
        invoke(fixture.gateway(), fixture.claim(), fixture.proposed());
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_run_stop", Integer.class))
                .isZero();
        assertThat(output).hasValue(3);
    }

    @Test
    void v2LoopVerdictAndStopRollBackTogetherOnCommitFailure() {
        var fixture = loopProposal("AA", "A");
        db.execute(
                """
                CREATE FUNCTION fail_loop_stop() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected stop commit failure'; END $$;
                CREATE TRIGGER fail_loop_stop BEFORE INSERT ON agent_graph_run_stop FOR EACH ROW EXECUTE FUNCTION fail_loop_stop();
                """);
        assertThatThrownBy(() -> invoke(fixture.gateway(), fixture.claim(), fixture.proposed()))
                .isInstanceOf(DataAccessException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_loop_verdict WHERE"
                                        + " pattern<>'ALLOW'",
                                Integer.class))
                .isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_run_stop", Integer.class))
                .isZero();
        db.execute("DROP TRIGGER fail_loop_stop ON agent_graph_run_stop");
        assertLoopStopped(fixture, "REPEATED_ACTION_RESULT", 2);
    }

    @Test
    void v2LoopGroupsParallelSiblingsAndDetectsRepeatedWholeBatchRegardlessOfOrder() {
        var gateway =
                cognition(
                        new AtomicInteger(),
                        n ->
                                answer(
                                        n % 2 == 0
                                                ? "{\"action\":\"TOOL_CALLS\""
                                                      + ",\"tools\":[{\"tool\":\"lookup\",\"arguments\":{\"word\":\"b\"}},{\"tool\":\"lookup\",\"arguments\":{\"word\":\"a\"}}]}"
                                                : "{\"action\":\"TOOL_CALLS\""
                                                      + ",\"tools\":[{\"tool\":\"lookup\",\"arguments\":{\"word\":\"a\"}},{\"tool\":\"lookup\",\"arguments\":{\"word\":\"b\"}}]}"),
                        null,
                        40000);
        executions.reserve("alpha", 1, "b".repeat(64), 10, 20, 2);
        var claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var refs = new java.util.ArrayList<String>();
        refs.add(claim.binding().inputRef());
        for (int i = 0; i < 3; i++) {
            var model =
                    invoke(
                            gateway,
                            claim,
                            new Invocation("MODEL", "model:batch:" + i, List.copyOf(refs), null));
            refs.add(model.receiptRef());
            for (var intent : model.decision().tools().reversed()) {
                var request = new Invocation("TOOL", intent.argumentsRef(), List.of(), intent);
                if (i == 2) {
                    assertThatThrownBy(() -> invoke(gateway, claim, request))
                            .hasMessage("RUN_STOPPED");
                    assertThat(
                                    db.queryForObject(
                                            "SELECT count(*) FROM agent_tool_execution_audit",
                                            Integer.class))
                            .isEqualTo(4);
                    return;
                }
                refs.add(invoke(gateway, claim, request).receiptRef());
            }
        }
    }

    @Test
    void v2ConcurrentLoopVerdictsCommitOneStopWithoutSending() throws Exception {
        var fixture = loopProposal("AA", "A");
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 2; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    assertThatThrownBy(
                                                    () ->
                                                            invoke(
                                                                    fixture.gateway(),
                                                                    fixture.claim(),
                                                                    fixture.proposed()))
                                            .hasMessage("RUN_STOPPED");
                                    return null;
                                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_graph_loop_verdict WHERE"
                                        + " pattern<>'ALLOW'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_run_stop", Integer.class))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM agent_tool_execution_audit", Integer.class))
                .isEqualTo(2);
    }

    private Claim completedGraph(AtomicInteger calls, boolean reject) {
        var gateway =
                cognition(
                        calls,
                        n ->
                                n == 1
                                        ? toolResponse()
                                        : answer(
                                                reject
                                                        ? "{\"resultStatus\":\"REJECTED\"}"
                                                        : "{\"count\":2}"),
                        null,
                        40000);
        Claim claim = reserveAndClaim(1);
        Receipt model =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL", "model:1", List.of(claim.binding().inputRef()), null));
        Receipt tool =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "TOOL", "tool:1", List.of(), model.decision().tools().getFirst()));
        Receipt answer =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "MODEL",
                                "model:2",
                                List.of(
                                        claim.binding().inputRef(),
                                        model.receiptRef(),
                                        tool.receiptRef()),
                                null));
        Receipt validation =
                invoke(
                        gateway,
                        claim,
                        new Invocation(
                                "VALIDATE",
                                "validate:1",
                                List.of(
                                        answer.decision().candidateRef(),
                                        model.receiptRef(),
                                        tool.receiptRef(),
                                        answer.receiptRef()),
                                null));
        assertThat(
                        executions.acknowledge(
                                claim,
                                new Observation(
                                        validation.accepted() ? "CANDIDATE_READY" : "REJECTED",
                                        "cp:final",
                                        answer.decision().candidateRef(),
                                        validation.receiptRef())))
                .isTrue();
        return claim;
    }

    private GraphCompletionService completion(boolean crash) {
        var mapper = new ObjectMapper();
        return new GraphCompletionService(
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                        .JdbcGraphCompletionStore(named, executions, codec),
                completionVersions,
                completionTools,
                new GraphCandidateGate(
                        new AgentOutputSchemaValidator(mapper),
                        mapper,
                        new CheckpointContextPolicy(4096)),
                (run, output) -> {
                    db.update(
                            "INSERT INTO completion_test_outbox VALUES(?,?,?,?)",
                            run.tenantCode(),
                            run.id(),
                            run.currentAttemptId(),
                            output);
                    if (crash) {
                        throw new IllegalStateException("crash before transaction commit");
                    }
                },
                mapper);
    }

    private GraphCognitionGateway cognition(
            AtomicInteger calls,
            IntFunction<ModelProviderResponse> responses,
            String outputSchema,
            int budget) {
        return cognition(calls, responses, outputSchema, budget, request -> "{\"count\":2}");
    }

    private GraphCognitionGateway cognition(
            AtomicInteger calls,
            IntFunction<ModelProviderResponse> responses,
            String outputSchema,
            int budget,
            java.util.function.Function<AgentTool.Request, String> toolOutput) {
        return cognition(calls, responses, outputSchema, budget, toolOutput, false);
    }

    private GraphCognitionGateway cognition(
            AtomicInteger calls,
            IntFunction<ModelProviderResponse> responses,
            String outputSchema,
            int budget,
            java.util.function.Function<AgentTool.Request, String> toolOutput,
            boolean extra) {
        if (extra) {
            db.update(
                    "INSERT INTO agent_tool_definition"
                            + " VALUES('lookup_extra','Extra','{}',true,'MCP','LOW',false,true)");
            db.update("INSERT INTO agent_tool_tenant_grant VALUES('alpha','lookup_extra',true)");
            db.update(
                    "UPDATE agent_definition_version SET"
                            + " tool_set_json='[\"lookup\",\"lookup_extra\"]' WHERE"
                            + " tenant_code='alpha'");
        }
        db.update(
                "UPDATE agent_run SET"
                        + " input_snapshot_json='{\"input\":{\"amount\":20000.01,\"word\":\"hi\"}}'"
                        + " WHERE execution_backend='NATIVE'");
        db.update(
                "UPDATE agent_definition_version SET output_schema=? WHERE tenant_code='alpha' AND"
                    + " NOT EXISTS(SELECT 1 FROM agent_graph_execution WHERE tenant_code='alpha')",
                outputSchema);
        var mapper = new ObjectMapper();
        var schemas = new AgentOutputSchemaValidator(mapper);
        var governance = new JdbcAgentToolGovernanceRepository(named);
        AgentTool tool =
                new AgentTool() {
                    public String name() {
                        return "lookup";
                    }

                    public Result execute(Request request) {
                        return new Result(toolOutput.apply(request), request.idempotencyKey());
                    }
                };
        AgentTool extraTool =
                new AgentTool() {
                    public String name() {
                        return "lookup_extra";
                    }

                    public Result execute(Request request) {
                        return new Result(toolOutput.apply(request), request.idempotencyKey());
                    }
                };
        var registry =
                new AgentToolRegistry(
                        extra ? List.of(tool, extraTool) : List.of(tool),
                        governance,
                        governance,
                        schemas,
                        mapper);
        if (outputSchema != null && outputSchema.contains("x-graph-routing")) {
            var frozen =
                    List.copyOf(
                            governance.findAuthorized(
                                    "alpha",
                                    extra
                                            ? java.util.Set.of("lookup", "lookup_extra")
                                            : java.util.Set.of("lookup")));
            var snapshots =
                    new io.github.illuseahashmap.agent.runtime.application.port
                            .AgentToolSnapshotRepository() {
                        public void freeze(
                                String tenant,
                                long version,
                                List<
                                                io.github.illuseahashmap.agent.runtime.application
                                                        .port.AgentToolDefinition>
                                        definitions) {
                            throw new UnsupportedOperationException();
                        }

                        public List<
                                        io.github.illuseahashmap.agent.runtime.application.port
                                                .AgentToolDefinition>
                                find(String tenant, long version) {
                            return frozen;
                        }
                    };
            registry =
                    new AgentToolRegistry(
                            extra ? List.of(tool, extraTool) : List.of(tool),
                            governance,
                            governance,
                            schemas,
                            mapper,
                            List.of(),
                            snapshots);
        }
        var versions = mock(AgentDefinitionVersionRepository.class);
        var version =
                new AgentDefinitionVersion(
                        10L,
                        "alpha",
                        1,
                        1,
                        AgentVersionStatus.PUBLISHED,
                        AgentExecutionMode.PLATFORM_AGENT,
                        1L,
                        "fixture",
                        "Return a grounded answer",
                        120,
                        AgentFailurePolicy.FAIL_PROCESS,
                        null,
                        outputSchema,
                        "reviewer",
                        "reviewer",
                        null,
                        null,
                        null,
                        extra ? "[\"lookup\",\"lookup_extra\"]" : "[\"lookup\"]");
        when(versions.findByVersionId("alpha", 10)).thenReturn(Optional.of(version));
        completionVersions = versions;
        completionTools = registry;
        var providers = mock(AgentProviderRepository.class);
        when(providers.findById("alpha", 1))
                .thenReturn(
                        Optional.of(
                                new AgentProvider(
                                        1L,
                                        "alpha",
                                        "fixture",
                                        "Fixture",
                                        AgentProviderType.MOCK,
                                        "https://unused.invalid",
                                        "fixture",
                                        true,
                                        false,
                                        null,
                                        null,
                                        null)));
        ModelProviderPort model =
                new ModelProviderPort() {
                    public AgentProviderType providerType() {
                        return AgentProviderType.MOCK;
                    }

                    public ModelProviderResponse invoke(ModelProviderRequest request) {
                        lastModelInput.set(request.userInput());
                        lastModelPrompt.set(request.systemPrompt());
                        return responses.apply(calls.incrementAndGet());
                    }
                };
        return new GraphCognitionGateway(
                new GraphToolGateway(executions, references, registry, codec),
                executions,
                new JdbcGraphCognitionStore(named, executions, codec),
                versions,
                providers,
                (tenant, id) -> {
                    throw new AssertionError("no credentials");
                },
                new ModelProviderRegistry(List.of(model)),
                registry,
                schemas,
                new CheckpointContextPolicy(budget),
                mapper,
                codec);
    }

    private Receipt invoke(GraphCognitionGateway gateway, Claim claim, Invocation request) {
        return gateway.invoke(
                        claim.capability(),
                        new InvokeRequest(claim.binding(), claim.generation(), request))
                .receipt();
    }

    private ModelProviderResponse answer(String content) {
        return new ModelProviderResponse(content, "fixture", "request", "stop", 1, 1, 0, 1);
    }

    private ModelProviderResponse toolResponse() {
        return new ModelProviderResponse(
                "",
                "fixture",
                "request",
                "tool_calls",
                1,
                1,
                0,
                1,
                new ModelProviderResponse.ToolCall("lookup", "{\"word\":\"hi\"}", "call"));
    }
}
