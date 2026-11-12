package io.github.illuseahashmap.workflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.infrastructure.persistence.JdbcAgentDefinitionVersionRepository;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.runtime.application.AgentOutputSchemaValidator;
import io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry;
import io.github.illuseahashmap.agent.runtime.application.checkpoint.CheckpointContextPolicy;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphCandidateGate;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphCompletionService;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.Invocation;
import io.github.illuseahashmap.agent.runtime.application.port.GraphDispatchPort.Observation;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCognitionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCompletionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphExecutionStore;
import io.github.illuseahashmap.workflow.event.JdbcReliableEventPublisher;
import io.github.illuseahashmap.workflow.process.application.AgentCompletionRecoveryService;
import io.github.illuseahashmap.workflow.process.application.AgentOutputMappingResolver;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.AgentTaskExecutionListener;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.FlowableAgentWorkflowExecutionAdapter;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flowable.engine.ProcessEngine;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Actual migrations + graph receipt storage + production Outbox/Inbox + real Flowable; no external model. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="WORKFLOW_LANGGRAPH_JDBC_URL", matches=".+")
class GraphCompletionFlowableIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final GraphGatewayCodec codec = new GraphGatewayCodec();
    private JdbcTemplate admin;
    private String database;
    private NamedParameterJdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ProcessEngine engine;
    private JdbcGraphExecutionStore executions;
    private GraphCompletionService completion;
    private AgentFlowableCompletionProcessor processor;
    private AgentCompletionEventStore outbox;

    @BeforeAll void start() {
        String url = System.getenv("WORKFLOW_LANGGRAPH_JDBC_URL");
        String user = System.getenv("WORKFLOW_LANGGRAPH_JDBC_USER");
        String password = System.getenv().getOrDefault("WORKFLOW_LANGGRAPH_JDBC_PASSWORD", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        database = "graph_flow_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        String isolated = "jdbc:postgresql://" + java.net.URI.create(url.substring(5)).getRawAuthority() + "/" + database;
        Flyway.configure().dataSource(isolated, user, password).locations("classpath:db/migration/platform").load().migrate();
        var ds = new DriverManagerDataSource(isolated, user, password);
        var manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        jdbc = new NamedParameterJdbcTemplate(ds);
        var cfg = new SpringProcessEngineConfiguration();
        cfg.setTransactionManager(manager);
        cfg.setDataSource(ds).setDatabaseSchemaUpdate("true").setAsyncExecutorActivate(false);
        engine = cfg.buildProcessEngine();
        engine.getRepositoryService().createDeployment().addString("graph.bpmn20.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="graph-test">
                  <process id="graph_completion" isExecutable="true">
                    <startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="agent"/>
                    <receiveTask id="agent"/><sequenceFlow id="b" sourceRef="agent" targetRef="review"/>
                    <userTask id="review" name="人工复核"/><sequenceFlow id="c" sourceRef="review" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """).deploy();
        executions = new JdbcGraphExecutionStore(jdbc, codec);
        var publisher = new AgentRunEventPublisherAdapter(new JdbcReliableEventPublisher(jdbc, List.of()), json);
        completion = new GraphCompletionService(new JdbcGraphCompletionStore(jdbc, executions, codec),
                new JdbcAgentDefinitionVersionRepository(jdbc), mock(AgentToolRegistry.class),
                new GraphCandidateGate(new AgentOutputSchemaValidator(json), json, new CheckpointContextPolicy(4096)), publisher, json);
        processor = new AgentFlowableCompletionProcessor(jdbc, new AgentCompletionRecoveryService(new JdbcAgentCompletionRunAdapter(jdbc),
                new FlowableAgentWorkflowExecutionAdapter(jdbc, engine.getRuntimeService()), new AgentOutputMappingResolver(json)), json);
        outbox = new AgentCompletionEventStore(jdbc, tx);
    }

    @AfterAll void stop() {
        if (engine != null) { engine.close(); }
        if (admin != null && database != null) { admin.execute("DROP DATABASE " + database); }
    }

    @Test void graphResultOutboxReplayAdvancesFlowableExactlyOnce() {
        var fixture = fixture(false);
        assertThat(completion.completeNext()).isTrue();
        assertThat(value("SELECT status FROM agent_run WHERE id=?", fixture.run())).isEqualTo("SUCCEEDED");
        assertThat(engine.getTaskService().createTaskQuery().processInstanceId(fixture.process()).count()).isZero();
        UUID event = claim();
        deliver(event);
        deliver(event); // lost response, same event redelivery
        assertThat(engine.getTaskService().createTaskQuery().processInstanceId(fixture.process()).count()).isEqualTo(1);
        assertThat(value("SELECT workflow_resumed_at::text FROM agent_run WHERE id=?", fixture.run())).isNotNull();
        assertThat(value("SELECT status FROM platform_outbox_event WHERE event_id=?", event)).isEqualTo("DELIVERED");
        assertThat(engine.getRuntimeService().getVariable(fixture.process(), "decision")).isEqualTo("APPROVE");
        assertThat(completion.completeNext()).isFalse();
    }

    @Test void crashAfterFlowableTriggerRollsBackInboxAndProcessThenRetriesWithoutModel() {
        var fixture = fixture(false);
        completion.completeNext();
        UUID event = claim();
        jdbc.getJdbcTemplate().execute("""
                CREATE FUNCTION crash_inbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected after Flowable trigger'; END $$;
                CREATE TRIGGER crash_inbox BEFORE UPDATE ON platform_inbox_event FOR EACH ROW EXECUTE FUNCTION crash_inbox();
                """);
        try { assertThatThrownBy(() -> deliver(event)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.getJdbcTemplate().execute("DROP TRIGGER crash_inbox ON platform_inbox_event"); }
        assertThat(engine.getTaskService().createTaskQuery().processInstanceId(fixture.process()).count()).isZero();
        assertThat(engine.getRuntimeService().createExecutionQuery().executionId(fixture.execution()).singleResult().getActivityId()).isEqualTo("agent");
        assertThat(value("SELECT workflow_resumed_at::text FROM agent_run WHERE id=?", fixture.run())).isNull();
        assertThat(value("SELECT status FROM platform_outbox_event WHERE event_id=?", event)).isEqualTo("PROCESSING");
        deliver(event);
        assertThat(engine.getTaskService().createTaskQuery().processInstanceId(fixture.process()).count()).isEqualTo(1);
        assertThat(jdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM agent_graph_model_call WHERE run_id=?", Integer.class, fixture.run())).isEqualTo(1);
    }

    @Test void lateCompletionCannotAdvanceAnotherActivationOfSameNode() {
        var fixture = fixture(false);
        completion.completeNext();
        engine.getRuntimeService().setVariableLocal(fixture.execution(), AgentTaskExecutionListener.ACTIVATION_VARIABLE, "new-activation");
        deliver(claim());
        assertThat(engine.getTaskService().createTaskQuery().processInstanceId(fixture.process()).count()).isZero();
        assertThat(value("SELECT workflow_resumed_at::text FROM agent_run WHERE id=?", fixture.run())).isNull();
    }

    @Test void rejectedGraphUsesExistingManualReviewPolicyWithoutPublishingAnAnswer() {
        var fixture = fixture(true);
        completion.completeNext();
        deliver(claim());
        assertThat(value("SELECT status FROM agent_run WHERE id=?", fixture.run())).isEqualTo("FAILED");
        assertThat(value("SELECT output_snapshot_json::text FROM agent_run WHERE id=?", fixture.run())).isNull();
        assertThat(engine.getRuntimeService().getVariable(fixture.process(), "agentReviewRequired")).isEqualTo(true);
        assertThat(engine.getTaskService().createTaskQuery().processInstanceId(fixture.process()).count()).isEqualTo(1);
    }

    private Fixture fixture(boolean reject) {
        String suffix = UUID.randomUUID().toString();
        var db = jdbc.getJdbcTemplate();
        var runtime = engine.getRuntimeService();
        var process = runtime.startProcessInstanceByKey("graph_completion");
        var execution = runtime.createExecutionQuery().processInstanceId(process.getId()).activityId("agent").singleResult();
        runtime.setVariableLocal(execution.getId(), AgentTaskExecutionListener.ACTIVATION_VARIABLE, suffix);
        long definition = db.queryForObject("INSERT INTO agent_definition(tenant_code,agent_code,agent_name) VALUES('alpha',?,'Graph fixture') RETURNING id", Long.class, suffix);
        long version = db.queryForObject("""
                INSERT INTO agent_definition_version(tenant_code,definition_id,version,status,execution_mode,tool_set_json)
                VALUES('alpha',?,1,'PUBLISHED','PLATFORM_AGENT','[]') RETURNING id
                """, Long.class, definition);
        long run = db.queryForObject("""
                INSERT INTO agent_run(tenant_code,idempotency_key,agent_version_id,status,trigger_type,process_instance_id,execution_id,
                    activity_id,activity_activation_id,deadline_at,input_snapshot_json,requested_by,output_mapping_json,process_failure_policy)
                VALUES('alpha',?,?,'QUEUED','FLOWABLE',?,?,'agent',?,clock_timestamp()+interval '10 minutes',
                    '{"input":{"amount":20000.01}}','reviewer','{"result":"decision"}','MANUAL_REVIEW') RETURNING id
                """, Long.class, suffix, version, process.getId(), execution.getId(), suffix);
        executions.reserve("alpha", run, "a".repeat(64), 5, 0);
        var claim = executions.claim("alpha", "fixture", 90).orElseThrow();
        assertThat(claim.binding().runId()).isEqualTo(run);
        var authority = executions.authorize(claim.capability(), claim.binding(), claim.generation());
        var cognition = new JdbcGraphCognitionStore(jdbc, executions, codec);
        var model = new Invocation("MODEL", "model:1", List.of(claim.binding().inputRef()), null);
        var started = cognition.beginModel(authority, model, "b".repeat(64));
        cognition.saveResponse(authority, model, started.ticket(), new ModelProviderResponse(
                reject ? "{\"resultStatus\":\"REJECTED\"}" : "{\"result\":\"APPROVE\"}", "fixture", "request", "stop", 1, 1, 0, 1));
        var candidate = cognition.finishModel(authority, model, null, null);
        var validation = cognition.finishValidation(authority, new Invocation("VALIDATE", "validate:1",
                List.of(candidate.decision().candidateRef(), candidate.receiptRef()), null), !reject, reject ? "AGENT_RESULT_BUSINESS_REJECTED" : "ACCEPTED");
        executions.acknowledge(claim, new Observation(reject ? "REJECTED" : "CANDIDATE_READY", "cp:final",
                candidate.decision().candidateRef(), validation.receiptRef()));
        return new Fixture(run, process.getId(), execution.getId());
    }
    private UUID claim() { return outbox.claim("graph-test", 1, Duration.ofSeconds(30)).getFirst(); }
    private void deliver(UUID event) { tx.executeWithoutResult(s -> processor.process(event, "graph-test")); }
    private String value(String sql, Object id) { return jdbc.getJdbcTemplate().queryForObject(sql, String.class, id); }
    private record Fixture(long run, String process, String execution) { }
}
