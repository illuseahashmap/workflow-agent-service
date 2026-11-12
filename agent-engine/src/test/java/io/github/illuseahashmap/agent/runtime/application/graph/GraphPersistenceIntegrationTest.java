package io.github.illuseahashmap.agent.runtime.application.graph;

import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.ToolIntent;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.InvokeRequest;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Receipt;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCognitionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcAgentToolGovernanceRepository;
import io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.agent.runtime.application.checkpoint.CheckpointContextPolicy;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderPort;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.provider.domain.AgentProvider;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderRepository;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.definition.domain.AgentVersionStatus;
import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;
import io.github.illuseahashmap.agent.definition.domain.AgentFailurePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.runtime.application.port.GraphDispatchPort.Claim;
import io.github.illuseahashmap.agent.runtime.application.port.GraphDispatchPort.Observation;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcAgentRunExecutionRepository;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphExecutionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphReferenceStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Executes actual V65-V68 on isolated PostgreSQL. Earlier tables are a minimal fixture, not full Flyway upgrade acceptance. */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_LANGGRAPH_JDBC_URL", matches = ".+")
class GraphPersistenceIntegrationTest {
    private final GraphGatewayCodec codec = new GraphGatewayCodec();
    private final java.util.concurrent.atomic.AtomicReference<String> lastModelInput = new java.util.concurrent.atomic.AtomicReference<>();
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private NamedParameterJdbcTemplate named;
    private JdbcGraphExecutionStore executions;
    private JdbcGraphReferenceStore references;
    private String schema;
    private String url;
    private String user;
    private String password;

    @BeforeEach void initialize() throws Exception {
        url = System.getenv("WORKFLOW_LANGGRAPH_JDBC_URL");
        user = System.getenv().getOrDefault("WORKFLOW_LANGGRAPH_JDBC_USER", "postgres");
        password = System.getenv().getOrDefault("WORKFLOW_LANGGRAPH_JDBC_PASSWORD", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "graph_store_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        named = new NamedParameterJdbcTemplate(new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password));
        db = named.getJdbcTemplate();
        db.execute("""
                CREATE TABLE agent_definition_version(id BIGINT,tenant_code TEXT,status TEXT,tool_set_json JSONB,PRIMARY KEY(id,tenant_code));
                CREATE TABLE agent_run(id BIGINT,tenant_code TEXT,agent_version_id BIGINT,status TEXT,current_attempt_id BIGINT,
                    deadline_at TIMESTAMPTZ,available_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                    input_snapshot_json JSONB,requested_by TEXT,process_instance_id TEXT,PRIMARY KEY(id,tenant_code));
                CREATE TABLE agent_run_attempt(tenant_code TEXT,agent_run_id BIGINT);
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
        db.execute(Files.readString(Path.of("../platform-migrations/src/main/resources/db/migration/platform/V65__add_graph_execution_handoff.sql")));
        db.execute(Files.readString(Path.of("../platform-migrations/src/main/resources/db/migration/platform/V66__add_graph_cognition_receipts.sql")));
        db.execute(Files.readString(Path.of("../platform-migrations/src/main/resources/db/migration/platform/V67__add_graph_capacity_governance.sql")));
        String admissionMigration = Files.readString(Path.of(
                "../platform-migrations/src/main/resources/db/migration/platform/V68__enforce_run_admission_and_bind_call_accounts.sql"));
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(named.getJdbcTemplate().getDataSource()))
                .executeWithoutResult(status -> db.execute(admissionMigration));
        db.execute("ALTER TABLE agent_definition_version ADD COLUMN provider_id BIGINT DEFAULT 1");
        reconnectStores();
    }

    @AfterEach void cleanup() {
        if (admin != null && schema != null) { admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    @Test void admissionCountsBothBackendsPausedRunsAndTerminalRetriesAtomically() {
        assertThat(admitted()).isEqualTo(3);
        executions.reserve("alpha", 1, "a".repeat(64), 10, 20);
        db.update("UPDATE agent_run_admission SET hard_limit=3 WHERE scope='GLOBAL'");
        db.update("UPDATE agent_run SET status='PAUSED' WHERE id=1");
        assertThat(admitted()).isEqualTo(3);
        assertThatThrownBy(() -> insertRun(4, "beta")).isInstanceOf(DataAccessException.class).hasMessageContaining("capacity exhausted");
        db.update("UPDATE agent_run SET status='FAILED' WHERE id=2");
        assertThat(admitted()).isEqualTo(2);
        insertRun(4, "beta");
        assertThatThrownBy(() -> db.update("UPDATE agent_run SET status='QUEUED' WHERE id=2"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("capacity exhausted");
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=2", String.class)).isEqualTo("FAILED");
        assertThat(admitted()).isEqualTo(3);
        db.update("DELETE FROM agent_run WHERE id=4");
        db.update("UPDATE agent_run SET status='QUEUED' WHERE id=2");
        assertThat(admitted()).isEqualTo(3);
    }

    @Test void admissionChargesNeitherConflictNoOpNorRolledBackSubmission() {
        db.update("INSERT INTO agent_run(id,tenant_code,status) VALUES(1,'alpha','QUEUED') ON CONFLICT DO NOTHING");
        assertThat(admitted()).isEqualTo(3);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(named.getJdbcTemplate().getDataSource()));
        tx.executeWithoutResult(status -> {
            insertRun(4, "beta");
            status.setRollbackOnly();
        });
        assertThat(admitted()).isEqualTo(3);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_run WHERE id=4", Integer.class)).isZero();
        db.update("UPDATE agent_run_admission SET hard_limit=2 WHERE scope='TENANT:alpha'");
        assertThatThrownBy(() -> insertRun(4, "alpha")).isInstanceOf(DataAccessException.class);
        insertRun(5, "beta");
        assertThat(admitted()).isEqualTo(4);
    }

    @Test void concurrentNativeSubmissionsCannotOverbookLastGlobalSlot() throws Exception {
        db.update("UPDATE agent_run_admission SET hard_limit=4 WHERE scope='GLOBAL'");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.IntStream.range(4, 6).mapToObj(id -> pool.submit(() -> {
                start.await();
                try {
                    insertRun(id, "beta");
                    return true;
                } catch (DataAccessException full) { return false; }
            })).toList();
            start.countDown();
            int winners = 0;
            for (var future : futures) { if (future.get(10, TimeUnit.SECONDS)) { winners++; } }
            assertThat(winners).isEqualTo(1);
        }
        assertThat(admitted()).isEqualTo(4);
    }

    @Test void dispatchOnlyClaimsCompatibleArtifacts() {
        executions.reserve("alpha", 1, "a".repeat(64), 10, 20);
        assertThat(executions.claimNext("worker", 90, "b".repeat(64))).isEmpty();
        assertThat(executions.claimNext("worker", 90, "a".repeat(64))).isPresent();
    }

    @Test void resourceBindingsShareAccountAndCannotMoveOutstandingCalls() {
        db.update("UPDATE agent_definition_version SET provider_id=2 WHERE tenant_code='beta'");
        var config = new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration(named);
        var settings = new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration.Configuration(
                List.of(new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration.Resource("account:shared", 1)),
                List.of(new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration.Binding("alpha", "PROVIDER", "1", "account:shared"),
                        new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration.Binding("beta", "PROVIDER", "2", "account:shared")));
        config.configure(settings);
        Claim one = reserveAndClaim(1);
        executions.reserve("beta", 3, "a".repeat(64), 10, 20);
        Claim two = executions.claim("beta", "worker-beta", 90).orElseThrow();
        var invocation = new Invocation("MODEL", "model:1", List.of(one.binding().inputRef()), null);
        var first = new InvokeRequest(one.binding(), one.generation(), invocation);
        var second = new InvokeRequest(two.binding(), two.generation(), new Invocation("MODEL", "model:1", List.of(two.binding().inputRef()), null));
        var capacity = new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCallCapacityStore(named, executions, codec);
        var permit = capacity.acquire(executions.authorize(one.capability(), one.binding(), one.generation()), invocation, () -> config.apply(first)).orElseThrow();
        assertThat(capacity.acquire(executions.authorize(two.capability(), two.binding(), two.generation()), second.request(), () -> config.apply(second))).isEmpty();
        var moved = new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration.Configuration(
                List.of(new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration.Resource("account:new", 1)),
                List.of(new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration.Binding("alpha", "PROVIDER", "1", "account:new")));
        assertThatThrownBy(() -> config.configure(moved)).isInstanceOf(GraphGatewayException.class);
        assertThat(config.apply(first)).containsExactly("account:shared");
        capacity.completed(permit);
        config.configure(moved);
        assertThat(config.apply(first)).containsExactly("account:new");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_resource_audit", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> db.update("DELETE FROM agent_graph_resource_audit")).isInstanceOf(DataAccessException.class);
        db.update("DELETE FROM agent_graph_resource_binding WHERE tenant_code='alpha'");
        assertThatThrownBy(() -> config.apply(first)).isInstanceOf(GraphGatewayException.class);
    }

    @Test void mcpAccountComesFromPublishedTenantSnapshotNotToolSuppliedHints() {
        db.execute("""
                CREATE TABLE mcp_connector_version(id BIGINT,tenant_code TEXT,connector_id BIGINT);
                CREATE TABLE mcp_tool_catalog_version(id BIGINT,tenant_code TEXT,connector_version_id BIGINT,status TEXT);
                CREATE TABLE mcp_tool_snapshot(id BIGINT,tenant_code TEXT,catalog_version_id BIGINT,tool_name TEXT);
                INSERT INTO mcp_connector_version VALUES(8,'alpha',50);
                INSERT INTO mcp_tool_catalog_version VALUES(9,'alpha',8,'PUBLISHED');
                INSERT INTO mcp_tool_snapshot VALUES(11,'alpha',9,'count_words');
                """);
        var config = new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration(named);
        config.configure(new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration.Configuration(
                List.of(new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration.Resource("mcp:directory-account", 2)),
                List.of(new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration.Binding(
                        "alpha", "CONNECTOR", "50", "mcp:directory-account"))));
        var claim = reserveAndClaim(1);
        var operation = new Invocation("TOOL", "tool:1", List.of(claim.binding().inputRef()),
                new ToolIntent("mcp:11:count_words", "arguments:one", "b".repeat(64)));
        assertThat(config.apply(new InvokeRequest(claim.binding(), claim.generation(), operation))).containsExactly("mcp:directory-account");
        var beta = executions.reserve("beta", 3, "a".repeat(64), 10, 20);
        assertThatThrownBy(() -> config.apply(new InvokeRequest(beta, 1, operation))).isInstanceOf(GraphGatewayException.class);
        db.update("UPDATE mcp_tool_catalog_version SET status='DRAFT'");
        assertThatThrownBy(() -> config.apply(new InvokeRequest(claim.binding(), claim.generation(), operation))).isInstanceOf(GraphGatewayException.class);
    }

    @Test void accountSnapshotRemainsBoundedAcrossMultiplePatches() {
        db.update("INSERT INTO agent_graph_call_resource SELECT 'account:'||n,1 FROM generate_series(1,1000) n");
        var config = new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration(named);
        assertThat(config.read().resources()).hasSize(1000);
        assertThatThrownBy(() -> config.configure(
                new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration.Configuration(
                        List.of(new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphResourceConfiguration.Resource("account:extra", 1)), List.of())))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(config.read().resources()).hasSize(1000);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_resource_audit", Integer.class)).isZero();
    }

    private long admitted() { return db.queryForObject("SELECT used FROM agent_run_admission WHERE scope='GLOBAL'", Long.class); }

    @Test void migrationBackfillSeesForcedRlsRowsWithNonSuperuserOwner() throws Exception {
        String role = "migration_" + UUID.randomUUID().toString().replace("-", "");
        String isolated = role + "_schema";
        admin.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
        admin.execute("CREATE SCHEMA " + isolated + " AUTHORIZATION " + role);
        try (var connection = DriverManager.getConnection(url, user, password); var stmt = connection.createStatement()) {
            stmt.execute("SET ROLE " + role);
            stmt.execute("SET search_path=" + isolated);
            stmt.execute("CREATE TABLE agent_run(id BIGINT,tenant_code TEXT,status TEXT)");
            stmt.execute("CREATE TABLE agent_graph_call_resource(resource_key VARCHAR(256) PRIMARY KEY)");
            stmt.execute("INSERT INTO agent_run VALUES(1,'alpha','QUEUED'),(2,'beta','PAUSED')");
            stmt.execute("ALTER TABLE agent_run ENABLE ROW LEVEL SECURITY");
            stmt.execute("ALTER TABLE agent_run FORCE ROW LEVEL SECURITY");
            stmt.execute("CREATE POLICY tenant_scope ON agent_run USING(current_setting('app.system_worker',true)='true')");
            connection.setAutoCommit(false);
            stmt.execute(Files.readString(Path.of(
                    "../platform-migrations/src/main/resources/db/migration/platform/V68__enforce_run_admission_and_bind_call_accounts.sql")));
            connection.commit();
            try (var rs = stmt.executeQuery("SELECT used FROM agent_run_admission WHERE scope='GLOBAL'")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(2);
            }
            try (var rs = stmt.executeQuery("SELECT count(*) FROM agent_run")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero(); // local elevation ended with the migration transaction
            }
            connection.rollback();
        } finally {
            admin.execute("DROP SCHEMA " + isolated + " CASCADE");
            admin.execute("DROP ROLE " + role);
        }
    }
    private void insertRun(long id, String tenant) {
        db.update("""
                INSERT INTO agent_run(id,tenant_code,agent_version_id,status,deadline_at,input_snapshot_json,requested_by,process_instance_id)
                VALUES(?,?,10,'QUEUED',clock_timestamp()+interval '1 hour','{}','reviewer',?)
                """,
                id, tenant, "process-" + id);
    }

    @Test void reservationIsIdempotentImmutableAndExcludesNativeWorkers() {
        var binding = executions.reserve("alpha", 1, "a".repeat(64), 10, 20);
        assertThat(executions.reserve("alpha", 1, "a".repeat(64), 10, 20)).isEqualTo(binding);
        assertThatThrownBy(() -> executions.reserve("alpha", 1, "b".repeat(64), 10, 20)).isInstanceOf(GraphGatewayException.class);
        db.update("UPDATE agent_run SET status='CANCELLED' WHERE id<>1");
        assertThat(new JdbcAgentRunExecutionRepository(named).lockNextAvailable(Instant.now())).isEmpty();
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_dispatch", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> db.update("UPDATE agent_graph_execution SET principal_id='other'" )).isInstanceOf(DataAccessException.class);
    }

    @Test void attemptedOrUnpublishedRunsCannotBeAdopted() {
        db.update("INSERT INTO agent_run_attempt VALUES ('alpha',1)");
        assertThatThrownBy(() -> executions.reserve("alpha", 1, "a".repeat(64), 10, 20)).isInstanceOf(GraphGatewayException.class);
        db.update("UPDATE agent_definition_version SET status='DRAFT' WHERE tenant_code='alpha'");
        assertThatThrownBy(() -> executions.reserve("alpha", 2, "a".repeat(64), 10, 20)).isInstanceOf(GraphGatewayException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_execution", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT execution_backend FROM agent_run WHERE id=1", String.class)).isEqualTo("NATIVE");
    }

    @Test void dispatchInsertFailureCannotLeaveAnAdoptedRunWithoutCommand() {
        db.execute("""
                CREATE FUNCTION reject_dispatch_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected dispatch failure' USING ERRCODE='23514'; END $$;
                CREATE TRIGGER reject_dispatch_insert BEFORE INSERT ON agent_graph_dispatch
                    FOR EACH ROW EXECUTE FUNCTION reject_dispatch_insert();
                """);
        assertThatThrownBy(() -> executions.reserve("alpha", 1, "a".repeat(64), 10, 20)).isInstanceOf(DataAccessException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_execution", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT execution_backend FROM agent_run WHERE id=1", String.class)).isEqualTo("NATIVE");
    }

    @Test void capabilityIsHashedRunBoundAndRedacted() {
        Claim first = reserveAndClaim(1);
        Claim second = reserveAndClaim(2);
        assertThat(executions.authorize(first.capability(), first.binding(), first.generation()).principalId()).isEqualTo("reviewer");
        assertThat(db.queryForObject("SELECT capability_hash FROM agent_graph_execution WHERE run_id=1", String.class))
                .isEqualTo(GraphGatewayCodec.sha256(first.capability())).doesNotContain(first.capability());
        assertThat(first.toString()).doesNotContain(first.capability());
        assertThatThrownBy(() -> executions.authorize(first.capability(), second.binding(), second.generation())).isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(() -> executions.authorize("browser-token", first.binding(), first.generation())).isInstanceOf(GraphGatewayException.class);
    }

    @Test void expiredDeliveryReusesCommandButFencesOldGeneration() {
        Claim old = reserveAndClaim(1);
        db.update("UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1 second'");
        assertThat(executions.renew(old, 60)).isFalse();
        reconnectStores();
        Claim replacement = executions.claim("alpha", "worker-two", 60).orElseThrow();
        assertThat(replacement.commandId()).isEqualTo(old.commandId());
        assertThat(replacement.generation()).isEqualTo(old.generation() + 1);
        assertThat(replacement.capability()).isNotEqualTo(old.capability());
        assertThatThrownBy(() -> executions.authorize(old.capability(), old.binding(), old.generation())).isInstanceOf(GraphGatewayException.class);
        assertThat(executions.acknowledge(old, observed())).isFalse();
        assertThat(executions.defer(old, "checkpoint:old", "wait:old", Instant.now())).isFalse();
        assertThat(executions.renew(replacement, 60)).isTrue();
    }

    @Test void deferredWaitReleasesAuthorityAndSurvivesRestartWithoutAttempt() {
        Claim old = reserveAndClaim(1);
        assertThat(executions.defer(old, "checkpoint:1", "wait:1", Instant.now().plusSeconds(60))).isTrue();
        assertThatThrownBy(() -> executions.authorize(old.capability(), old.binding(), old.generation())).isInstanceOf(GraphGatewayException.class);
        reconnectStores();
        assertThat(executions.claim("alpha", "worker-two", 60)).isEmpty();
        db.update("UPDATE agent_graph_dispatch SET available_at=clock_timestamp()-interval '1 second'");
        Claim next = executions.claim("alpha", "worker-two", 60).orElseThrow();
        assertThat(next.commandId()).isNotEqualTo(old.commandId());
        assertThat(next.checkpointId()).isEqualTo("checkpoint:1");
        assertThat(next.waitId()).isEqualTo("wait:1");
        assertThat(db.queryForObject("SELECT count(*) FROM agent_run_attempt", Integer.class)).isZero();
    }

    @Test void acknowledgeIsFencedAndCannotCompleteBusinessRun() {
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
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class)).isEqualTo("QUEUED");
        assertThat(db.queryForObject("SELECT observation_json FROM agent_graph_dispatch", String.class)).contains("CANDIDATE_READY");
        var wrongDatabase = new NamedParameterJdbcTemplate(new DriverManagerDataSource(url, user, password));
        assertThatThrownBy(() -> new JdbcGraphReferenceStore(wrongDatabase, executions, codec)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void cancellationRevocationAndInputChangesInvalidateAuthority() {
        Claim claim = reserveAndClaim(1);
        db.update("UPDATE agent_run SET input_snapshot_json='{}' WHERE id=1");
        assertThatThrownBy(() -> executions.authorize(claim.capability(), claim.binding(), claim.generation())).isInstanceOf(GraphGatewayException.class);
        db.update("UPDATE agent_run SET input_snapshot_json='{" + "\"word\":\"purchase-one\"}' WHERE id=1");
        db.update("UPDATE agent_run SET status='CANCELLED' WHERE id=1");
        assertThat(executions.renew(claim, 60)).isFalse();
        Claim other = reserveAndClaim(2);
        executions.revoke("alpha", 2);
        assertThatThrownBy(() -> executions.authorize(other.capability(), other.binding(), other.generation())).isInstanceOf(GraphGatewayException.class);
        assertThat(executions.claim("alpha", "worker-two", 60)).isEmpty();
    }

    @Test void concurrentClaimHasExactlyOneWinner() throws Exception {
        executions.reserve("alpha", 1, "a".repeat(64), 10, 20);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                String worker = "worker-" + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return executions.claim("alpha", worker, 60).isPresent();
                }));
            }
            start.countDown();
            int winners = 0;
            for (var future : futures) { if (future.get(10, TimeUnit.SECONDS)) { winners++; } }
            assertThat(winners).isEqualTo(1);
        }
    }

    @Test void immutableArgumentsCannotCrossRunsOrLogicalSteps() {
        Claim first = reserveAndClaim(1);
        Claim second = reserveAndClaim(2);
        var run = executions.authorize(first.capability(), first.binding(), first.generation());
        String body = "{\"word\":\"one\"}";
        String ref = references.recordArguments(run, "tool:1", "lookup", body);
        assertThat(references.recordArguments(run, "tool:1", "lookup", body)).isEqualTo(ref);
        assertThatThrownBy(() -> references.recordArguments(run, "tool:1", "lookup", "{}" )).isInstanceOf(GraphGatewayException.class);
        Invocation invocation = tool(ref, body);
        assertThat(references.arguments(run, invocation).json()).isEqualTo(body);
        var other = executions.authorize(second.capability(), second.binding(), second.generation());
        assertThatThrownBy(() -> references.arguments(other, invocation)).isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(() -> references.arguments(run, new Invocation("TOOL", "tool:2", List.of(), invocation.tool())))
                .isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(() -> db.update("UPDATE agent_graph_reference SET body='{}'")).isInstanceOf(DataAccessException.class);
        executions.revoke("alpha", 1);
        assertThatThrownBy(() -> references.arguments(run, invocation)).isInstanceOf(GraphGatewayException.class);
    }

    @Test void receiptMustPointToSuccessfulExistingLedgerAndReplayKeepsReference() {
        Claim claim = reserveAndClaim(1);
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        String ref = references.recordArguments(run, "tool:1", "lookup", "{}");
        var request = tool(ref, "{}");
        String output = "{\"count\":2}";
        String digest = GraphGatewayCodec.sha256(output);
        assertThatThrownBy(() -> references.recordReceipt(run, request, "1:tool:1:lookup", digest)).isInstanceOf(GraphGatewayException.class);
        db.update("""
                INSERT INTO agent_tool_execution_audit(tenant_code,tool_code,idempotency_key,status,output_snapshot)
                VALUES('alpha','lookup','1:tool:1:lookup','SUCCEEDED',?)
                """, output);
        String receipt = references.recordReceipt(run, request, "1:tool:1:lookup", digest);
        reconnectStores();
        assertThat(references.recordReceipt(run, request, "1:tool:1:lookup", digest)).isEqualTo(receipt);
        assertThatThrownBy(() -> references.recordReceipt(run, request, "2:tool:1:lookup", digest)).isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(() -> references.recordReceipt(run, request, "1:tool:1:lookup", "f".repeat(64))).isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(() -> references.recordReceipt(run, tool("graph:forged", "{}"), "1:tool:1:lookup", digest))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_reference WHERE kind='TOOL_RECEIPT'", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT body FROM agent_graph_reference WHERE kind='TOOL_RECEIPT'", String.class))
                .startsWith("{\"audit_id\":").contains(",\"idempotency_key\":\"1:tool:1:lookup\",\"output_digest\":");
    }

    @Test void referenceInsertRollingPastLeaseExpiryRollsBackEntireTransaction() {
        Claim claim = reserveAndClaim(1);
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        db.execute("""
                CREATE FUNCTION delay_graph_reference() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM pg_sleep(1.2); RETURN NEW; END $$;
                CREATE TRIGGER delay_graph_reference BEFORE INSERT ON agent_graph_reference
                    FOR EACH ROW EXECUTE FUNCTION delay_graph_reference();
                """);
        db.update("UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()+interval '1 second'");
        long started = System.nanoTime();
        assertThatThrownBy(() -> references.recordArguments(run, "tool:1", "lookup", "{}" )).isInstanceOf(GraphGatewayException.class);
        assertThat(System.nanoTime() - started).isGreaterThan(TimeUnit.SECONDS.toNanos(1));
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_reference", Integer.class)).isZero();
    }

    @Test void actualMigrationEnforcesRlsForNonOwnerRoleWithoutAdminBypass() throws Exception {
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
            try (var connection = DriverManager.getConnection(url, user, password); var stmt = connection.createStatement()) {
                stmt.execute("SET ROLE " + role);
                stmt.execute("SET app.platform_admin='true'");
                try (var rs = stmt.executeQuery("SELECT count(*) FROM " + schema + ".agent_graph_execution")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
                stmt.execute("SET app.tenant_code='alpha'");
                stmt.execute("INSERT INTO " + schema + ".agent_run(id,tenant_code,status) VALUES(4,'alpha','QUEUED')");
                assertThat(admitted()).isEqualTo(4);
                try (var rs = stmt.executeQuery("SELECT count(*) FROM " + schema + ".agent_run_admission")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
                stmt.execute("UPDATE " + schema + ".agent_run SET status='FAILED' WHERE id=4");
                assertThat(admitted()).isEqualTo(3);
                try (var rs = stmt.executeQuery("SELECT count(*) FROM " + schema + ".agent_graph_capacity_policy")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
                try (var rs = stmt.executeQuery("SELECT count(*) FROM " + schema + ".agent_graph_execution")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(2);
                }
                String insert = "INSERT INTO " + schema + ".agent_graph_reference"
                        + "(tenant_code,run_id,reference,binding_digest,kind,operation_id,tool_code,content_digest,body) VALUES ";
                stmt.execute(insert + "('alpha',1,'own-ref','digest','ARGUMENTS','tool:99','lookup','digest','{}')");
                assertThatThrownBy(() -> stmt.execute(insert + "('beta',3,'foreign-ref','digest','ARGUMENTS','tool:99','lookup','digest','{}')"))
                        .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("row-level security");
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
        return new Invocation("TOOL", "tool:1", List.of(), new ToolIntent("lookup", ref, GraphGatewayCodec.sha256(json)));
    }
    private Observation observed() { return new Observation("CANDIDATE_READY", "checkpoint:1", "candidate:1", "validation:1"); }

    @Test void durableModelToolModelValidationUsesExistingRegistryAndNeverCompletesRun() {
        var calls = new AtomicInteger();
        GraphCognitionGateway gateway = cognition(calls, n -> n == 1 ? toolResponse() : answer("{\"count\":2}"), null, 40000);
        db.update("UPDATE agent_run SET input_snapshot_json='{\"input\":{\"amount\":20000.000000000001,\"word\":\"hi\"}}' WHERE id=1");
        Claim claim = reserveAndClaim(1);
        Invocation first = new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null);
        Receipt model = invoke(gateway, claim, first);
        assertThat(lastModelInput.get()).contains("20000.000000000001");
        Receipt tool = invoke(gateway, claim, new Invocation("TOOL", "tool:1", List.of(), model.decision().tools().getFirst()));
        Invocation second = new Invocation("MODEL", "model:2", List.of(claim.binding().inputRef(), model.receiptRef(), tool.receiptRef()), null);
        Receipt candidate = invoke(gateway, claim, second);
        Invocation validation = new Invocation("VALIDATE", "validate:1",
                List.of(candidate.decision().candidateRef(), model.receiptRef(), tool.receiptRef(), candidate.receiptRef()), null);
        Receipt accepted = invoke(gateway, claim, validation);
        assertThat(accepted.accepted()).isTrue();
        reconnectStores();
        GraphCognitionGateway restored = cognition(calls, n -> { throw new AssertionError("must not re-answer"); }, null, 4096);
        assertThat(invoke(restored, claim, second)).isEqualTo(candidate);
        assertThat(invoke(restored, claim, validation)).isEqualTo(accepted);
        assertThat(calls).hasValue(2);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_tool_execution_audit WHERE status='SUCCEEDED'", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class)).isEqualTo("QUEUED");
    }

    @Test void rawResponseSurvivesFailureBeforeDecisionAndDoesNotNeedModelAgain() {
        var calls = new AtomicInteger();
        var gateway = cognition(calls, n -> answer("{\"count\":2}"), null, 40000);
        Claim claim = reserveAndClaim(1);
        db.execute("""
                CREATE FUNCTION fail_model_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.receipt_json IS NOT NULL THEN RAISE EXCEPTION 'injected crash'; END IF; RETURN NEW; END $$;
                CREATE TRIGGER fail_model_receipt BEFORE UPDATE ON agent_graph_model_call FOR EACH ROW EXECUTE FUNCTION fail_model_receipt();
                """);
        Invocation request = new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null);
        assertThatThrownBy(() -> invoke(gateway, claim, request)).isInstanceOf(DataAccessException.class);
        assertThat(db.queryForObject("SELECT response_json IS NOT NULL AND receipt_json IS NULL FROM agent_graph_model_call", Boolean.class)).isTrue();
        db.execute("DROP TRIGGER fail_model_receipt ON agent_graph_model_call");
        reconnectStores();
        var restored = cognition(calls, n -> { throw new AssertionError("must replay raw response"); }, null, 4096);
        assertThat(invoke(restored, claim, request).decision().candidateRef()).isNotBlank();
        assertThat(calls).hasValue(1);
    }

    @Test void rejectedModelCannotCallToolOrEnterRepairAndRejectionIsDurable() {
        var calls = new AtomicInteger();
        var rejected = new ModelProviderResponse("not JSON", "fixture", null, "business_rejected", 1, 1, 0, 1,
                new ModelProviderResponse.ToolCall("lookup", "{}", "call"));
        var gateway = cognition(calls, n -> rejected, "{\"type\":\"object\"}", 40000);
        Claim claim = reserveAndClaim(1);
        Receipt candidate = invoke(gateway, claim, new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null));
        assertThat(candidate.decision().tools()).isEmpty();
        Invocation request = new Invocation("VALIDATE", "validate:1", List.of(candidate.decision().candidateRef(), candidate.receiptRef()), null);
        Receipt failure = invoke(gateway, claim, request);
        assertThat(failure.accepted()).isFalse();
        assertThat(invoke(gateway, claim, request)).isEqualTo(failure);
        assertThat(calls).hasValue(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_tool_execution_audit", Integer.class)).isZero();
    }

    @Test void missingProcurementCitationCannotApproveAmountAbove20000() {
        var calls = new AtomicInteger();
        String schema = """
                {"type":"object","properties":{"decision":{"type":"string"},"citations":{"type":"array","items":{"type":"string"}}},
                 "x-workflow-citation-claim":{"version":"1","amountField":"amount","above":20000,"decisionField":"decision",
                     "disposition":"CONDITIONAL","citationsField":"citations","requiredChunks":["necessary-clause"]}}
                """;
        var gateway = cognition(calls, n -> answer("{\"decision\":\"MANDATORY\",\"citations\":[\"necessary-clause\"]}"), schema, 40000);
        Claim claim = reserveAndClaim(1);
        Receipt candidate = invoke(gateway, claim, new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null));
        assertThat(invoke(gateway, claim, new Invocation("VALIDATE", "validate:1",
                List.of(candidate.decision().candidateRef(), candidate.receiptRef()), null)).accepted()).isFalse();
        assertThat(db.queryForObject("SELECT reason_code FROM agent_graph_validation", String.class)).isEqualTo("AGENT_DECISION_UNVERIFIED");
    }

    @Test void cognitionRejectsOmittedOrForeignReceiptsAndStopsAfterCandidate() {
        var calls = new AtomicInteger();
        var gateway = cognition(calls, n -> answer("{\"count\":2}"), null, 40000);
        Claim one = reserveAndClaim(1);
        Claim two = reserveAndClaim(2);
        Receipt candidate = invoke(gateway, one, new Invocation("MODEL", "model:1", List.of(one.binding().inputRef()), null));
        assertThatThrownBy(() -> invoke(gateway, two, new Invocation("VALIDATE", "validate:1",
                List.of(candidate.decision().candidateRef(), candidate.receiptRef()), null))).isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(() -> invoke(gateway, one, new Invocation("VALIDATE", "validate:1",
                List.of(candidate.decision().candidateRef()), null))).isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(() -> invoke(gateway, one, new Invocation("MODEL", "model:2",
                List.of(one.binding().inputRef(), candidate.receiptRef()), null))).isInstanceOf(GraphGatewayException.class);
        assertThat(calls).hasValue(1);
    }

    @Test void duplicateStatusCannotHideBusinessRejection() {
        var calls = new AtomicInteger();
        String ambiguous = "{\"resultStatus\":\"REJECTED\",\"resultStatus\":\"SUCCESS\",\"count\":2}";
        var gateway = cognition(calls, n -> answer(ambiguous), "{\"type\":\"object\"}", 40000);
        Claim claim = reserveAndClaim(1);
        Receipt candidate = invoke(gateway, claim, new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null));
        assertThat(invoke(gateway, claim, new Invocation("VALIDATE", "validate:1",
                List.of(candidate.decision().candidateRef(), candidate.receiptRef()), null)).accepted()).isFalse();
        assertThat(db.queryForObject("SELECT reason_code FROM agent_graph_validation", String.class)).isEqualTo("AGENT_RESULT_STATUS_INVALID");
        assertThat(calls).hasValue(1);
    }

    @Test void responseThatCannotBeReadAfterRestartCannotBeCommitted() {
        Claim claim = reserveAndClaim(1);
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        var store = new JdbcGraphCognitionStore(named, executions, codec);
        var request = new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null);
        var started = store.beginModel(run, request, "a".repeat(64));
        assertThatThrownBy(() -> store.saveResponse(run, request, started.ticket(), answer("x".repeat(70000))))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(db.queryForObject("SELECT response_json IS NULL FROM agent_graph_model_call", Boolean.class)).isTrue();
    }

    @Test void modelReplyAfterRevocationCannotPublishResponseOrToolIntent() throws Exception {
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var gateway = cognition(calls, n -> {
            entered.countDown();
            try { if (!released.await(5, TimeUnit.SECONDS)) { throw new AssertionError("release required"); } }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
            return toolResponse();
        }, null, 40000);
        Claim claim = reserveAndClaim(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(() -> invoke(gateway, claim,
                    new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null)));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                executions.revoke("alpha", 1);
            } finally { released.countDown(); }
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(GraphGatewayException.class);
        }
        assertThat(db.queryForObject("SELECT response_json IS NULL FROM agent_graph_model_call", Boolean.class)).isTrue();
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_reference", Integer.class)).isZero();
    }

    @Test void noRawResponseRequiresNewGenerationAndPhysicalBudgetIncludesRetries() {
        var binding = executions.reserve("alpha", 1, "a".repeat(64), 1, 20);
        Claim claim = executions.claim("alpha", "worker", 60).orElseThrow();
        var run = executions.authorize(claim.capability(), binding, claim.generation());
        var store = new JdbcGraphCognitionStore(named, executions, codec);
        var request = new Invocation("MODEL", "model:1", List.of(binding.inputRef()), null);
        var started = store.beginModel(run, request, "a".repeat(64));
        assertThat(started.ticket()).isNotBlank();
        assertThatThrownBy(() -> store.beginModel(run, request, "a".repeat(64))).hasMessage("MODEL_IN_PROGRESS");
        db.update("UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1 second'");
        Claim next = executions.claim("alpha", "worker-two", 60).orElseThrow();
        var newRun = executions.authorize(next.capability(), binding, next.generation());
        assertThatThrownBy(() -> store.beginModel(newRun, request, "a".repeat(64))).hasMessage("MODEL_BUDGET_EXHAUSTED");
        assertThatThrownBy(() -> store.saveResponse(run, request, started.ticket(), answer("old"))).hasMessage("AUTHORITY_DENIED");
    }

    @Test void revokedToolResultsCannotBeReusedForModelOrValidation() {
        var calls = new AtomicInteger();
        var gateway = cognition(calls, n -> n == 1 ? toolResponse() : answer("{\"count\":2}"), null, 40000);
        Claim claim = reserveAndClaim(1);
        Receipt model = invoke(gateway, claim, new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null));
        Receipt tool = invoke(gateway, claim, new Invocation("TOOL", "tool:1", List.of(), model.decision().tools().getFirst()));
        var second = new Invocation("MODEL", "model:2", List.of(claim.binding().inputRef(), model.receiptRef(), tool.receiptRef()), null);
        Receipt candidate = invoke(gateway, claim, second);
        db.update("UPDATE agent_tool_tenant_grant SET enabled=false WHERE tenant_code='alpha'");
        assertThatThrownBy(() -> invoke(gateway, claim, second)).isInstanceOf(GraphGatewayException.class);
        assertThatThrownBy(() -> invoke(gateway, claim, new Invocation("VALIDATE", "validate:1",
                List.of(candidate.decision().candidateRef(), model.receiptRef(), tool.receiptRef(), candidate.receiptRef()), null)))
                .isInstanceOf(GraphGatewayException.class);
        assertThat(calls).hasValue(2);
    }

    @Test void pythonGraphRecoversLostCandidateAndValidationRepliesWithRealJdbcAuthority() throws Exception {
        var calls = new AtomicInteger();
        var gateway = cognition(calls, n -> n == 1 ? toolResponse() : answer("{\"count\":2}"), null, 40000);
        Claim claim = reserveAndClaim(1);
        var protocol = new io.github.illuseahashmap.agent.runtime.interfaces.rest.GraphGatewayProtocol(gateway, codec);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 8);
        var workers = Executors.newFixedThreadPool(4);
        server.setExecutor(workers);
        var droppedCandidate = new java.util.concurrent.atomic.AtomicBoolean();
        var droppedValidation = new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext(io.github.illuseahashmap.agent.runtime.interfaces.rest.GraphGatewayProtocol.PREFIX, exchange -> {
            try (exchange) {
                byte[] body = exchange.getRequestBody().readNBytes(GraphGatewayCodec.MAX_WIRE_BYTES + 1);
                var response = protocol.exchange(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"), body);
                var root = codec.read(body, com.fasterxml.jackson.databind.JsonNode.class);
                String operation = root.path("request").path("operation_id").asText();
                if (response.status() == 200 && (("model:2".equals(operation) && droppedCandidate.compareAndSet(false, true))
                        || ("validate:1".equals(operation) && droppedValidation.compareAndSet(false, true)))) { return; }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.status(), response.body().length);
                exchange.getResponseBody().write(response.body());
            }
        });
        server.start();
        try {
            Path log = Files.createTempFile("graph-cognition-", ".log");
            var command = new ProcessBuilder("uv", "run", "--frozen", "python", "tests/java_cognition_probe.py")
                    .directory(Path.of("../agent-runtime-langgraph").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            command.environment().put("GRAPH_COGNITION_BINDING", new String(codec.write(claim.binding()), java.nio.charset.StandardCharsets.UTF_8));
            command.environment().put("GRAPH_COGNITION_GENERATION", Long.toString(claim.generation()));
            command.environment().put("GRAPH_COGNITION_CAPABILITY", claim.capability());
            command.environment().put("GRAPH_COGNITION_URL", "http://127.0.0.1:" + server.getAddress().getPort());
            Process process = command.start();
            try {
                assertThat(process.waitFor(45, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
                assertThat(Files.readString(log)).contains("JAVA_COGNITION_GRAPH_OK");
            } finally {
                if (process.isAlive()) { process.destroyForcibly(); }
            }
            assertThat(calls).hasValue(2);
            assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_validation", Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT count(*) FROM agent_tool_execution_audit", Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=1", String.class)).isEqualTo("QUEUED");
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
    }

    @Test void outstandingIncludesDeferredRevokedAndIdempotentReservations() {
        db.update("UPDATE agent_graph_capacity_policy SET max_outstanding=1,tenant_outstanding=1");
        Claim first = reserveAndClaim(1);
        executions.defer(first, "cp:one", "wait:one", Instant.now().plusSeconds(10));
        assertThat(executions.reserve("alpha", 1, "a".repeat(64), 10, 20)).isEqualTo(first.binding());
        assertThatThrownBy(() -> executions.reserve("beta", 3, "a".repeat(64), 10, 20)).hasMessage("CAPACITY_EXHAUSTED");
        executions.revoke("alpha", 1);
        assertThatThrownBy(() -> executions.reserve("beta", 3, "a".repeat(64), 10, 20)).hasMessage("CAPACITY_EXHAUSTED");
        db.update("UPDATE agent_run SET status='CANCELLED' WHERE id=1");
        assertThat(executions.reserve("beta", 3, "a".repeat(64), 10, 20)).isNotNull();
    }

    @Test void twoSchedulersCannotTakeLastGlobalSlotAndShrinkDoesNotEvict() throws Exception {
        executions.reserve("alpha", 1, "a".repeat(64), 10, 20);
        executions.reserve("beta", 3, "a".repeat(64), 10, 20);
        db.update("UPDATE agent_graph_capacity_policy SET max_active=1");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                gate.await();
                return executions.claimNext("one", 60);
            });
            var b = pool.submit(() -> {
                gate.await();
                return new JdbcGraphExecutionStore(named, codec).claimNext("two", 60);
            });
            gate.countDown();
            var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
            Claim winner = results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            assertThat(executions.renew(winner, 60)).isTrue();
            assertThat(executions.claimNext("third", 60)).isEmpty();
            executions.defer(winner, "cp:one", "wait:one", Instant.now());
            assertThat(executions.claimNext("third", 60).orElseThrow().binding().tenantCode()).isNotEqualTo(winner.binding().tenantCode());
        }
    }

    @Test void tenantFairnessSurvivesRestartAndSkipsSaturatedTenant() {
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
        assertThat(db.queryForObject("SELECT count(*) FROM agent_run_attempt", Integer.class)).isZero();
    }

    @Test void sharedAccountPermitsAreAtomicAndUnknownsStayCountedAfterLeaseExpiry() throws Exception {
        Claim first = reserveAndClaim(1);
        Claim second = reserveAndClaim(2);
        db.update("INSERT INTO agent_graph_call_resource VALUES('account:shared',1),('account:fast',1)");
        var capacity = new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCallCapacityStore(named, executions, codec);
        var a = executions.authorize(first.capability(), first.binding(), first.generation());
        var b = executions.authorize(second.capability(), second.binding(), second.generation());
        var operation = new Invocation("MODEL", "model:1", List.of(first.binding().inputRef()), null);
        var other = new Invocation("MODEL", "model:1", List.of(second.binding().inputRef()), null);
        String token = capacity.acquire(a, operation, List.of("account:shared")).orElseThrow();
        capacity.uncertain(token);
        db.update("UPDATE agent_graph_execution SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE run_id=1");
        Claim newer = executions.claim("alpha", "successor", 60).orElseThrow();
        var current = executions.authorize(newer.capability(), newer.binding(), newer.generation());
        assertThat(capacity.acquire(current, operation, List.of("account:shared"))).isEmpty();
        assertThat(capacity.acquire(b, other, List.of("account:shared"))).isEmpty();
        String fast = capacity.acquire(b, other, List.of("account:fast")).orElseThrow();
        capacity.completed(fast);
        assertThat(capacity.reconcile(token, "receipt:remote-cancel-confirmed")).isTrue();
        assertThat(capacity.reconcile(token, "receipt:duplicate")).isFalse();
        String retry = capacity.acquire(current, operation, List.of("account:shared")).orElseThrow();
        capacity.completed(token); // old result cannot release the new token
        assertThat(capacity.acquire(b, other, List.of("account:shared"))).isEmpty();
        capacity.completed(retry);
        assertThat(capacity.acquire(b, other, List.of("account:shared"))).isPresent();
    }

    @Test void lastCallPermitRaceAndMultiResourceDenialLeaveNoPartialAllocation() throws Exception {
        Claim one = reserveAndClaim(1);
        Claim two = reserveAndClaim(2);
        db.update("INSERT INTO agent_graph_call_resource VALUES('account:shared',1),('connector:one',1)");
        var capacity = new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCallCapacityStore(named, executions, codec);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(one, two).stream().map(claim -> pool.submit(() -> {
                gate.await();
                return capacity.acquire(executions.authorize(claim.capability(), claim.binding(), claim.generation()),
                        new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null), List.of("connector:one", "account:shared"));
            })).toList();
            gate.countDown();
            assertThat(List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS))
                    .stream().filter(Optional::isPresent).count()).isEqualTo(1);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_call_permit", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_call_allocation", Integer.class)).isEqualTo(2);
    }

    @Test void capacityGatewayDefersWithoutCallingModelAndReleasesCompletedEnvelope() {
        var calls = new AtomicInteger();
        var delegate = cognition(calls, n -> answer("{\"count\":2}"), null, 40000);
        Claim first = reserveAndClaim(1);
        Claim second = reserveAndClaim(2);
        db.update("INSERT INTO agent_graph_call_resource VALUES('account:shared',1)");
        var capacity = new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCallCapacityStore(named, executions, codec);
        String held = capacity.acquire(executions.authorize(second.capability(), second.binding(), second.generation()),
                new Invocation("MODEL", "model:1", List.of(second.binding().inputRef()), null), List.of("account:shared")).orElseThrow();
        var gateway = new GraphCapacityGateway(delegate, executions, capacity, request -> List.of("account:shared"), codec);
        var request = new InvokeRequest(first.binding(), first.generation(), new Invocation("MODEL", "model:1", List.of(first.binding().inputRef()), null));
        var wait = gateway.invoke(first.capability(), request);
        assertThat(wait.deferred().reason()).isEqualTo("QUOTA");
        assertThat(calls).hasValue(0);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_model_call", Integer.class)).isZero();
        capacity.completed(held);
        assertThat(gateway.invoke(first.capability(), request).receipt()).isNotNull();
        assertThat(gateway.invoke(first.capability(), request).receipt()).isNotNull();
        assertThat(calls).hasValue(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_call_permit WHERE state<>'RELEASED'", Integer.class)).isZero();
    }

    @Test void capacityMissingPolicyAndArgumentConflictsFailClosed() {
        Claim claim = reserveAndClaim(1);
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        var capacity = new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCallCapacityStore(named, executions, codec);
        var operation = new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null);
        assertThatThrownBy(() -> capacity.acquire(run, operation, List.of("missing:account"))).isInstanceOf(GraphGatewayException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_call_permit", Integer.class)).isZero();
        db.update("INSERT INTO agent_graph_call_resource VALUES('account:shared',1)");
        String token = capacity.acquire(run, operation, List.of("account:shared")).orElseThrow();
        capacity.completed(token);
        assertThatThrownBy(() -> capacity.acquire(run, new Invocation("MODEL", "model:1", List.of("input:changed"), null),
                List.of("account:shared"))).isInstanceOf(GraphGatewayException.class);
    }

    @Test void durableResponsesBypassUnknownCapacityAndReconcileWithoutNewExternalCall() {
        var calls = new AtomicInteger();
        var delegate = cognition(calls, n -> toolResponse(), null, 40000);
        Claim claim = reserveAndClaim(1);
        var run = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        db.update("INSERT INTO agent_graph_call_resource VALUES('account:shared',1)");
        var capacity = new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCallCapacityStore(named, executions, codec);
        var operation = new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null);
        String token = capacity.acquire(run, operation, List.of("account:shared")).orElseThrow();
        var committed = invoke(delegate, claim, operation);
        capacity.uncertain(token); // crash/lost acknowledgement after response was committed
        var governed = new GraphCapacityGateway(delegate, executions, capacity, request -> List.of("account:shared"), codec);
        assertThat(governed.invoke(claim.capability(), new InvokeRequest(claim.binding(), claim.generation(), operation)).receipt()).isEqualTo(committed);
        assertThat(calls).hasValue(1);
        var tool = new Invocation("TOOL", "tool:1", List.of(), committed.decision().tools().getFirst());
        String toolToken = capacity.acquire(run, tool, List.of("account:shared")).orElseThrow();
        invoke(delegate, claim, tool);
        capacity.uncertain(toolToken);
        assertThat(governed.invoke(claim.capability(), new InvokeRequest(claim.binding(), claim.generation(), tool)).receipt()).isNotNull();
        assertThat(db.queryForObject("SELECT count(*) FROM agent_tool_execution_audit", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_call_permit WHERE state<>'RELEASED'", Integer.class)).isZero();
        db.update("UPDATE agent_tool_tenant_grant SET enabled=false");
        assertThatThrownBy(() -> governed.invoke(claim.capability(), new InvokeRequest(claim.binding(), claim.generation(), tool)))
                .isInstanceOf(GraphGatewayException.class).hasMessage("TOOL_REJECTED");
    }

    @Test void recoveryClassifiesOrphansButNeverFreesUnknownRemoteCapacity() {
        Claim claim = reserveAndClaim(1);
        db.update("INSERT INTO agent_graph_call_resource VALUES('account:shared',1)");
        var capacity = new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCallCapacityStore(named, executions, codec);
        String token = capacity.acquire(executions.authorize(claim.capability(), claim.binding(), claim.generation()),
                new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null), List.of("account:shared")).orElseThrow();
        assertThat(capacity.quarantineOrphans(10)).isZero();
        executions.revoke("alpha", 1);
        assertThat(capacity.quarantineOrphans(10)).isEqualTo(1);
        assertThat(capacity.quarantineOrphans(10)).isZero();
        assertThat(db.queryForObject("SELECT state FROM agent_graph_call_permit", String.class)).isEqualTo("QUARANTINED");
        capacity.completed(token); // a definitive late completion may settle its OWN envelope
        assertThat(db.queryForObject("SELECT state FROM agent_graph_call_permit", String.class)).isEqualTo("RELEASED");
    }

    @Test void lockedTenantDoesNotKeepOtherTenantsBehindHeadOfQueue() throws Exception {
        executions.reserve("alpha", 1, "a".repeat(64), 10, 20);
        executions.reserve("beta", 3, "a".repeat(64), 10, 20);
        try (var connection = DriverManager.getConnection(url + "?currentSchema=" + schema, user, password)) {
            connection.setAutoCommit(false);
            try (var stmt = connection.createStatement()) {
                stmt.executeQuery("SELECT id FROM agent_run WHERE id=1 FOR UPDATE");
                assertThat(executions.claimNext("worker", 60)).isEmpty();
                assertThat(executions.claimNext("worker", 60).orElseThrow().binding().tenantCode()).isEqualTo("beta");
            } finally { connection.rollback(); }
        }
    }

    @Test void uncertainProviderFailureDefersNewCallsButIndependentAccountContinues() {
        var calls = new AtomicInteger();
        var delegate = cognition(calls, n -> {
            if (n == 1) { throw new IllegalStateException("connection reset after possible submission"); }
            return answer("{\"count\":2}");
        }, null, 40000);
        Claim first = reserveAndClaim(1);
        Claim second = reserveAndClaim(2);
        db.update("INSERT INTO agent_graph_call_resource VALUES('account:slow',1),('account:fast',1)");
        var capacity = new io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCallCapacityStore(named, executions, codec);
        var gateway = new GraphCapacityGateway(delegate, executions, capacity,
                request -> List.of(request.binding().runId() == 1 ? "account:slow" : "account:fast"), codec);
        var one = new InvokeRequest(first.binding(), first.generation(), new Invocation("MODEL", "model:1", List.of(first.binding().inputRef()), null));
        assertThatThrownBy(() -> gateway.invoke(first.capability(), one)).isInstanceOf(IllegalStateException.class);
        assertThat(gateway.invoke(first.capability(), one).deferred().reason()).isEqualTo("QUOTA");
        assertThat(calls).hasValue(1);
        var two = new InvokeRequest(second.binding(), second.generation(), new Invocation("MODEL", "model:1", List.of(second.binding().inputRef()), null));
        assertThat(gateway.invoke(second.capability(), two).receipt()).isNotNull();
        assertThat(calls).hasValue(2);
        assertThat(db.queryForObject("SELECT count(*) FROM agent_graph_call_permit WHERE state='QUARANTINED'", Integer.class)).isEqualTo(1);
    }

    private GraphCognitionGateway cognition(AtomicInteger calls, IntFunction<ModelProviderResponse> responses, String outputSchema, int budget) {
        db.update("UPDATE agent_run SET input_snapshot_json='{\"input\":{\"amount\":20000.01,\"word\":\"hi\"}}' WHERE execution_backend='NATIVE'");
        var mapper = new ObjectMapper();
        var schemas = new AgentOutputSchemaValidator(mapper);
        var governance = new JdbcAgentToolGovernanceRepository(named);
        AgentTool tool = new AgentTool() {
            public String name() { return "lookup"; }
            public Result execute(Request request) { return new Result("{\"count\":2}", request.idempotencyKey()); }
        };
        var registry = new AgentToolRegistry(List.of(tool), governance, governance, schemas, mapper);
        var versions = mock(AgentDefinitionVersionRepository.class);
        var version = new AgentDefinitionVersion(10L, "alpha", 1, 1, AgentVersionStatus.PUBLISHED, AgentExecutionMode.PLATFORM_AGENT,
                1L, "fixture", "Return a grounded answer", 120, AgentFailurePolicy.FAIL_PROCESS, null, outputSchema,
                "reviewer", "reviewer", null, null, null, "[\"lookup\"]");
        when(versions.findByVersionId("alpha", 10)).thenReturn(Optional.of(version));
        var providers = mock(AgentProviderRepository.class);
        when(providers.findById("alpha", 1)).thenReturn(Optional.of(new AgentProvider(1L, "alpha", "fixture", "Fixture", AgentProviderType.MOCK,
                "https://unused.invalid", "fixture", true, false, null, null, null)));
        ModelProviderPort model = new ModelProviderPort() {
            public AgentProviderType providerType() { return AgentProviderType.MOCK; }
            public ModelProviderResponse invoke(ModelProviderRequest request) {
                lastModelInput.set(request.userInput());
                return responses.apply(calls.incrementAndGet());
            }
        };
        return new GraphCognitionGateway(new GraphToolGateway(executions, references, registry, codec), executions,
                new JdbcGraphCognitionStore(named, executions, codec), versions, providers, (tenant, id) -> { throw new AssertionError("no credentials"); },
                new ModelProviderRegistry(List.of(model)), registry, schemas, new CheckpointContextPolicy(budget), mapper, codec);
    }
    private Receipt invoke(GraphCognitionGateway gateway, Claim claim, Invocation request) {
        return gateway.invoke(claim.capability(), new InvokeRequest(claim.binding(), claim.generation(), request)).receipt();
    }
    private ModelProviderResponse answer(String content) { return new ModelProviderResponse(content, "fixture", "request", "stop", 1, 1, 0, 1); }
    private ModelProviderResponse toolResponse() {
        return new ModelProviderResponse("", "fixture", "request", "tool_calls", 1, 1, 0, 1,
                new ModelProviderResponse.ToolCall("lookup", "{\"word\":\"hi\"}", "call"));
    }
}
