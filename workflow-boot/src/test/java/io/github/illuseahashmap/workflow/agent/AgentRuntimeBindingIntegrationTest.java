package io.github.illuseahashmap.workflow.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.illuseahashmap.agent.definition.application.impl.AgentDefinitionServiceImpl;
import io.github.illuseahashmap.agent.runtime.application.dto.AgentManualRunCommand;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.impl.AgentRunSubmissionServiceImpl;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRunEventPublisher;
import io.github.illuseahashmap.agent.runtime.application.port.AgentRuntimeBinding.Configuration;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcAgentRunExecutionRepository;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcAgentRunOperationsRepository;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcAgentRuntimeBindingStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCompletionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphExecutionStore;
import io.github.illuseahashmap.workflow.knowledge.PostgresCitationPublicationAdapter;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class AgentRuntimeBindingIntegrationTest {
    @Test
    void frozenBackendAtomicSubmissionAndQueuedCancellation() throws Exception {
        P17RoutingFixtureTest.withDatabase(
                (url, user, password) -> {
                    Flyway.configure()
                            .dataSource(url, user, password)
                            .locations("classpath:db/migration/platform")
                            .load()
                            .migrate();
                    var ds = new DriverManagerDataSource(url, user, password);
                    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
                    var named = new NamedParameterJdbcTemplate(ds);
                    var db = named.getJdbcTemplate();
                    var p = new P17Pilot(named, tx, false);
                    var store = new JdbcAgentRuntimeBindingStore(named, true, "a".repeat(64));
                    var config = new Configuration("LANGGRAPH", "a".repeat(64), 2, 6, 4);
                    assertThat(store.read(P17Pilot.TENANT, p.policyVersion).backend())
                            .isEqualTo("NATIVE");
                    assertThatThrownBy(() -> store.read("other", p.policyVersion))
                            .hasMessageContaining("does not exist");
                    assertThatThrownBy(
                                    () ->
                                            store.configure(
                                                    P17Pilot.TENANT,
                                                    p.policyVersion,
                                                    config,
                                                    "operator"))
                            .hasMessageContaining("new draft");
                    var service =
                            new AgentDefinitionServiceImpl(
                                    p.definitions,
                                    p.versions,
                                    p.providers,
                                    p.tenant,
                                    p.buyer,
                                    P17Pilot.JSON,
                                    p.validator,
                                    p.registry,
                                    new PostgresCitationPublicationAdapter(named));
                    long definition =
                            db.queryForObject(
                                    "SELECT definition_id FROM agent_definition_version WHERE id=?",
                                    Long.class,
                                    p.policyVersion);
                    long version = tx.execute(s -> service.createDraft(definition)).id();
                    store.configure(P17Pilot.TENANT, version, config, "operator");
                    assertThatThrownBy(
                                    () ->
                                            db.update(
                                                    "UPDATE agent_version_runtime SET"
                                                        + " artifact_digest=NULL WHERE"
                                                        + " agent_version_id=?",
                                                    version))
                            .isInstanceOf(
                                    org.springframework.dao.DataIntegrityViolationException.class);
                    tx.executeWithoutResult(s -> service.publish(definition, version));
                    assertThatThrownBy(
                                    () ->
                                            db.update(
                                                    "DELETE FROM agent_version_runtime WHERE"
                                                            + " agent_version_id=?",
                                                    version))
                            .hasMessageContaining("immutable");
                    var submission =
                            new AgentRunSubmissionServiceImpl(
                                    p.definitions,
                                    p.versions,
                                    p.providers,
                                    new JdbcAgentRunExecutionRepository(named),
                                    p.tenant,
                                    p.buyer,
                                    P17Pilot.JSON,
                                    AgentRunEventPublisher.NOOP,
                                    store);
                    long before = db.queryForObject("SELECT count(*) FROM agent_run", Long.class);
                    assertThatThrownBy(
                                    () ->
                                            tx.execute(
                                                    s -> {
                                                        submission.submitManual(
                                                                new AgentManualRunCommand(
                                                                        definition, "P-17"));
                                                        throw new IllegalStateException(
                                                                "injected outer rollback");
                                                    }))
                            .hasMessageContaining("injected outer rollback");
                    assertThat(db.queryForObject("SELECT count(*) FROM agent_run", Long.class))
                            .isEqualTo(before);
                    assertThat(
                                    db.queryForObject(
                                            "SELECT count(*) FROM agent_graph_execution",
                                            Long.class))
                            .isZero();
                    var unavailable =
                            new JdbcAgentRuntimeBindingStore(named, false, "a".repeat(64));
                    assertThatThrownBy(
                                    () ->
                                            tx.execute(
                                                    s -> {
                                                        unavailable.beforeSubmit(
                                                                P17Pilot.TENANT, version);
                                                        return null;
                                                    }))
                            .hasMessageContaining("not available");
                    String role =
                            "binding_" + java.util.UUID.randomUUID().toString().replace("-", "");
                    db.execute("CREATE ROLE " + role);
                    db.execute("GRANT USAGE ON SCHEMA public TO " + role);
                    db.execute(
                            "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO "
                                    + role);
                    db.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO " + role);
                    long run;
                    try {
                        run =
                                tx.execute(
                                        s -> {
                                            db.execute("SET LOCAL ROLE " + role);
                                            db.execute(
                                                    "SELECT"
                                                        + " set_config('app.tenant_code','pilot',true),"
                                                        + "set_config('app.system_worker','false',true),set_config('app.platform_admin','false',true)");
                                            long created =
                                                    submission
                                                            .submitManual(
                                                                    new AgentManualRunCommand(
                                                                            definition, "P-17"))
                                                            .runId();
                                            assertThat(
                                                            db.queryForObject(
                                                                    "SELECT"
                                                                        + " current_setting('app.system_worker')",
                                                                    String.class))
                                                    .isEqualTo("false");
                                            assertThat(
                                                            db.queryForObject(
                                                                    "SELECT count(*) FROM"
                                                                        + " agent_graph_capacity_policy",
                                                                    Long.class))
                                                    .isZero();
                                            assertThat(
                                                            new JdbcAgentRunOperationsRepository(
                                                                            named)
                                                                    .cancelActive(
                                                                            P17Pilot.TENANT,
                                                                            created,
                                                                            "operator",
                                                                            "trace",
                                                                            "cancel test"))
                                                    .isTrue();
                                            assertThat(
                                                            db.queryForObject(
                                                                    "SELECT"
                                                                        + " current_setting('app.system_worker')",
                                                                    String.class))
                                                    .isEqualTo("false");
                                            return created;
                                        });
                    } finally {
                        db.execute("DROP OWNED BY " + role);
                        db.execute("DROP ROLE " + role);
                    }
                    assertThat(
                                    db.queryForObject(
                                            "SELECT execution_backend FROM agent_run WHERE id=?",
                                            String.class,
                                            run))
                            .isEqualTo("LANGGRAPH");
                    var operations = new JdbcAgentRunOperationsRepository(named);
                    tx.executeWithoutResult(
                            s ->
                                    assertThat(
                                                    operations.cancelActive(
                                                            P17Pilot.TENANT,
                                                            run,
                                                            "operator",
                                                            "trace",
                                                            "cancel test"))
                                            .isTrue());
                    assertThat(
                                    db.queryForObject(
                                            "SELECT status FROM agent_run WHERE id=?",
                                            String.class,
                                            run))
                            .isEqualTo("QUEUED");
                    assertThat(
                                    db.queryForObject(
                                            "SELECT reason FROM agent_graph_run_stop WHERE"
                                                    + " run_id=?",
                                            String.class,
                                            run))
                            .isEqualTo("OPERATOR_CANCELLED");
                    var codec = new GraphGatewayCodec();
                    var completion =
                            new JdbcGraphCompletionStore(
                                    named, new JdbcGraphExecutionStore(named, codec), codec);
                    assertThat(
                                    completion.completeNext(
                                            e -> {
                                                throw new AssertionError(
                                                        "Cancellation must not evaluate a model"
                                                                + " result");
                                            },
                                            AgentRunEventPublisher.NOOP))
                            .isTrue();
                    assertThat(
                                    db.queryForObject(
                                            "SELECT status FROM agent_run WHERE id=?",
                                            String.class,
                                            run))
                            .isEqualTo("CANCELLED");
                    assertThat(
                                    db.queryForObject(
                                            "SELECT current_attempt_id FROM agent_run WHERE id=?",
                                            Long.class,
                                            run))
                            .isNull();
                    assertThat(
                                    completion.completeNext(
                                            e -> {
                                                throw new AssertionError("Duplicate completion");
                                            },
                                            AgentRunEventPublisher.NOOP))
                            .isFalse();
                    long nextDraft = tx.execute(s -> service.createDraft(definition)).id();
                    assertThat(store.read(P17Pilot.TENANT, nextDraft)).isEqualTo(config);
                    assertThatThrownBy(
                                    () ->
                                            db.update(
                                                    "UPDATE agent_definition_version SET"
                                                        + " execution_mode='MODEL_ONLY' WHERE id=?",
                                                    nextDraft))
                            .hasMessageContaining("native runtime");
                    store.configure(
                            P17Pilot.TENANT, nextDraft, Configuration.nativeRuntime(), "operator");
                    db.update(
                            "UPDATE agent_definition_version SET execution_mode='MODEL_ONLY' WHERE"
                                    + " id=?",
                            nextDraft);
                    var token = java.util.UUID.randomUUID();
                    db.update(
                            "INSERT INTO"
                                + " agent_graph_call_permit(token,tenant_code,run_id,operation_id,request_digest,generation,state,tracking_version)"
                                + " VALUES(?,'pilot',?,'model:1',?,1,'QUARANTINED',1)",
                            token,
                            run,
                            "b".repeat(64));
                    var reconciliations =
                            new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                                    .JdbcGraphCallReconciliation(named);
                    var evidence =
                            new io.github.illuseahashmap.agent.runtime.infrastructure.persistence
                                    .JdbcGraphCallReconciliation.Evidence(
                                    token,
                                    "external-123",
                                    "CANCELLED",
                                    "https://evidence.example/receipts/123",
                                    "c".repeat(64));
                    var proposal = reconciliations.propose(evidence, "first-admin");
                    assertThat(reconciliations.pending()).hasSize(1);
                    assertThatThrownBy(
                                    () ->
                                            reconciliations.approve(
                                                    proposal, "c".repeat(64), "first-admin"))
                            .hasMessageContaining("different operator");
                    assertThatThrownBy(
                                    () ->
                                            reconciliations.approve(
                                                    proposal, "d".repeat(64), "second-admin"))
                            .hasMessageContaining("digest");
                    assertThat(
                                    db.queryForObject(
                                            "SELECT state FROM agent_graph_call_permit WHERE"
                                                    + " token=?",
                                            String.class,
                                            token))
                            .isEqualTo("QUARANTINED");
                    assertThat(reconciliations.approve(proposal, "c".repeat(64), "second-admin"))
                            .isTrue();
                    assertThat(reconciliations.approve(proposal, "c".repeat(64), "second-admin"))
                            .isFalse();
                    assertThat(reconciliations.pending()).isEmpty();
                    assertThat(
                                    db.queryForObject(
                                            "SELECT status FROM agent_run WHERE id=?",
                                            String.class,
                                            run))
                            .isEqualTo("CANCELLED");
                    assertThatThrownBy(
                                    () ->
                                            db.update(
                                                    "DELETE FROM"
                                                        + " agent_graph_call_reconciliation_approval"
                                                        + " WHERE reconciliation_id=?",
                                                    proposal))
                            .isInstanceOf(org.springframework.dao.DataAccessException.class);
                });
    }
}
