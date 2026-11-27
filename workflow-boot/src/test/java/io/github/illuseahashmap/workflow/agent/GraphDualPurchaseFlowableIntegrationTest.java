package io.github.illuseahashmap.workflow.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.illuseahashmap.agent.definition.application.dto.AgentDefinitionCommand;
import io.github.illuseahashmap.agent.definition.application.dto.AgentVersionCommand;
import io.github.illuseahashmap.agent.definition.application.impl.AgentDefinitionServiceImpl;
import io.github.illuseahashmap.agent.definition.domain.AgentExecutionMode;
import io.github.illuseahashmap.agent.definition.domain.AgentFailurePolicy;
import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderPort;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderRequest;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry;
import io.github.illuseahashmap.agent.runtime.application.checkpoint.CheckpointContextPolicy;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphCandidateGate;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphCapacityGateway;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphCognitionGateway;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphCompletionService;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayCodec;
import io.github.illuseahashmap.agent.runtime.application.graph.GraphToolGateway;
import io.github.illuseahashmap.agent.runtime.application.impl.AgentRunSubmissionServiceImpl;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcAgentRunExecutionRepository;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcAgentToolGovernanceRepository;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcAgentToolSnapshotRepository;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCallCapacityStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCognitionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphCompletionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphExecutionStore;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcGraphReferenceStore;
import io.github.illuseahashmap.workflow.agent.graph.GraphHostConfiguration;
import io.github.illuseahashmap.workflow.agent.graph.GraphHostController;
import io.github.illuseahashmap.workflow.event.JdbcReliableEventPublisher;
import io.github.illuseahashmap.workflow.knowledge.PostgresCitationPublicationAdapter;
import io.github.illuseahashmap.workflow.process.application.AgentCompletionRecoveryService;
import io.github.illuseahashmap.workflow.process.application.AgentInputMappingResolver;
import io.github.illuseahashmap.workflow.process.application.AgentOutputMappingResolver;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.AgentTaskExecutionListener;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.FlowableAgentWorkflowExecutionAdapter;

import org.flowable.engine.ProcessEngine;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flywaydb.core.Flyway;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Flowable submission, authenticated graph dispatch, capacity, and completion transport. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "WORKFLOW_LANGGRAPH_JDBC_URL", matches = ".+")
class GraphDualPurchaseFlowableIntegrationTest {
    private static final String TENANT = "pilot";
    private static final String TOKEN = "isolated-dual-worker-token-000000000000";
    private static final String MEMORY =
            """
            {"type":"object","x-graph-checklist":{"version":1,"subject_field":"word","required_facts":[
              {"id":"approval","tool":"lookup","value_field":"approved","expected":true}],"allow_correction":false},
             "x-graph-memory":{"version":1,"inline_characters":256,"summary_characters":512}}
            """;
    private final ObjectMapper json = new ObjectMapper();
    private final GraphGatewayCodec codec = new GraphGatewayCodec();
    private final ThreadLocal<String> lastModelInput = new ThreadLocal<>();
    private final ThreadLocal<String> lastModelPrompt = new ThreadLocal<>();
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private String database;
    private String checkpointDsn;
    private NamedParameterJdbcTemplate named;
    private TransactionTemplate tx;
    private ProcessEngine engine;
    private P17Pilot p;
    private JdbcGraphExecutionStore executions;
    private GraphCompletionService completion;
    private AgentFlowableCompletionProcessor processor;
    private AgentCompletionEventStore outbox;
    private AgentRunEventPublisherAdapter publisher;
    private final Map<Long, String> processes = new LinkedHashMap<>();

    @BeforeAll
    void start() throws Exception {
        String url = System.getenv("WORKFLOW_LANGGRAPH_JDBC_URL");
        String user = System.getenv("WORKFLOW_LANGGRAPH_JDBC_USER");
        String password = System.getenv().getOrDefault("WORKFLOW_LANGGRAPH_JDBC_PASSWORD", "");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        database = "dual_flow_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database);
        var uri = java.net.URI.create(url.substring(5));
        String isolated = "jdbc:postgresql://" + uri.getRawAuthority() + "/" + database;
        checkpointDsn =
                new java.net.URI(
                                "postgresql",
                                user + ":" + password,
                                uri.getHost(),
                                uri.getPort(),
                                "/" + database,
                                null,
                                null)
                        .toString();
        Flyway.configure()
                .dataSource(isolated, user, password)
                .locations("classpath:db/migration/platform")
                .load()
                .migrate();
        var ds = new DriverManagerDataSource(isolated, user, password);
        var manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        named = new NamedParameterJdbcTemplate(ds);
        db = named.getJdbcTemplate();
        p = new P17Pilot(named, tx, false, true);
        executions = new JdbcGraphExecutionStore(named, codec);
        publisher =
                new AgentRunEventPublisherAdapter(
                        new JdbcReliableEventPublisher(named, List.of()), json);
        var submission =
                new AgentRunSubmissionServiceImpl(
                        p.definitions,
                        p.versions,
                        p.providers,
                        new JdbcAgentRunExecutionRepository(named),
                        p.tenant,
                        p.buyer,
                        json,
                        publisher);
        var cfg = new SpringProcessEngineConfiguration();
        cfg.setTransactionManager(manager);
        cfg.setDataSource(ds).setDatabaseSchemaUpdate("true").setAsyncExecutorActivate(false);
        cfg.setBeans(
                Map.of(
                        "agentTaskExecutionListener",
                        new AgentTaskExecutionListener(
                                new AgentRunGatewayAdapter(submission),
                                p.tenant,
                                new AgentInputMappingResolver(json))));
        engine = cfg.buildProcessEngine();
        processor =
                new AgentFlowableCompletionProcessor(
                        named,
                        new AgentCompletionRecoveryService(
                                new JdbcAgentCompletionRunAdapter(named),
                                new FlowableAgentWorkflowExecutionAdapter(
                                        named, engine.getRuntimeService()),
                                new AgentOutputMappingResolver(json)),
                        json);
        outbox = new AgentCompletionEventStore(named, tx);
    }

    @AfterAll
    void stop() {
        if (engine != null) {
            engine.close();
        }
        if (admin != null && database != null) {
            admin.execute("DROP DATABASE " + database);
        }
    }

    @Test
    void flowableDualPurchaseCompressionAndGenerationRecovery() throws Exception {
        // Model and tool are scripted; all platform stores, snapshots, policies and transport are
        // real.
        exercise(new long[2]);
    }

    private GraphCognitionGateway cognition(
            AtomicInteger calls,
            java.util.function.IntFunction<ModelProviderResponse> responses,
            String schema,
            int budget,
            java.util.function.Function<AgentTool.Request, String> output) {
        db.update(
                "INSERT INTO"
                    + " agent_tool_definition(tool_code,tool_name,input_schema,read_only,enabled)"
                    + " VALUES('lookup','Dual purchase lookup','{\"type\":\"object\"}',true,true)");
        db.update(
                "INSERT INTO agent_tool_tenant_grant(tenant_code,tool_code,enabled)"
                        + " VALUES(?,'lookup',true)",
                TENANT);
        var governance = new JdbcAgentToolGovernanceRepository(named);
        AgentTool tool =
                new AgentTool() {
                    public String name() {
                        return "lookup";
                    }

                    public Result execute(Request request) {
                        return new Result(output.apply(request), request.idempotencyKey());
                    }
                };
        var registry =
                new AgentToolRegistry(
                        List.of(tool),
                        governance,
                        governance,
                        p.validator,
                        json,
                        List.of(),
                        new JdbcAgentToolSnapshotRepository(named));
        var service =
                new AgentDefinitionServiceImpl(
                        p.definitions,
                        p.versions,
                        p.providers,
                        p.tenant,
                        p.buyer,
                        json,
                        p.validator,
                        registry,
                        new PostgresCitationPublicationAdapter(named));
        long version =
                tx.execute(
                        s -> {
                            var def =
                                    service.create(
                                            new AgentDefinitionCommand(
                                                    "dual_flow",
                                                    "Dual flow",
                                                    "Combination acceptance",
                                                    true));
                            long id = p.versions.findLatest(TENANT, def.id()).orElseThrow().id();
                            service.updateDraft(
                                    def.id(),
                                    id,
                                    new AgentVersionCommand(
                                            AgentExecutionMode.PLATFORM_AGENT,
                                            p.provider,
                                            p.model,
                                            "Check original procurement facts",
                                            120,
                                            AgentFailurePolicy.FAIL_PROCESS,
                                            "{\"type\":\"object\"}",
                                            schema,
                                            List.of("lookup"),
                                            null));
                            return service.publish(def.id(), id).id();
                        });
        deploy(version);
        completion =
                new GraphCompletionService(
                        new JdbcGraphCompletionStore(named, executions, codec),
                        p.versions,
                        registry,
                        new GraphCandidateGate(
                                p.validator, json, new CheckpointContextPolicy(budget)),
                        publisher,
                        json);
        var model =
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
                new GraphToolGateway(
                        executions,
                        new JdbcGraphReferenceStore(named, executions, codec),
                        registry,
                        codec),
                executions,
                new JdbcGraphCognitionStore(named, executions, codec),
                p.versions,
                p.providers,
                p.credentials,
                new ModelProviderRegistry(List.of(model)),
                registry,
                p.validator,
                new CheckpointContextPolicy(budget),
                json,
                codec);
    }

    private void deploy(long version) {
        String xml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xmlns:flowable="http://flowable.org/bpmn" xmlns:workflow="http://workflow-agent.local/bpmn" targetNamespace="dual-flow">
                <process id="dual_purchase" isExecutable="true">
                <startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="agent"/>
                <receiveTask id="agent"><extensionElements>
                <workflow:agentTask agentVersionId="%s" inputMapping='{"word":"word"}'
                  outputMapping='{"word":"checkedWord","amount":"checkedAmount"}' processFailurePolicy="MANUAL_REVIEW" processWaitTimeoutSeconds="120"/>
                <flowable:executionListener event="start" delegateExpression="${agentTaskExecutionListener}"/>
                </extensionElements></receiveTask>
                <sequenceFlow id="b" sourceRef="agent" targetRef="gate"/><exclusiveGateway id="gate" default="ready"/>
                <sequenceFlow id="manual" sourceRef="gate" targetRef="MANUAL_REVIEW">
                  <conditionExpression xsi:type="tFormalExpression">${execution.getVariable('agentReviewRequired') == true}</conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="ready" sourceRef="gate" targetRef="READY_CHECK"/>
                <userTask id="READY_CHECK"/><userTask id="MANUAL_REVIEW"/>
                <sequenceFlow id="c" sourceRef="READY_CHECK" targetRef="end"/><sequenceFlow id="d" sourceRef="MANUAL_REVIEW" targetRef="end"/><endEvent id="end"/>
                </process></definitions>
                """
                        .formatted(version);
        engine.getRepositoryService()
                .createDeployment()
                .tenantId(TENANT)
                .addString("dual.bpmn20.xml", xml)
                .deploy();
        for (String subject : List.of("DUAL_A", "DUAL_B")) {
            var pi =
                    engine.getRuntimeService()
                            .startProcessInstanceByKeyAndTenantId(
                                    "dual_purchase", Map.of("word", subject), TENANT)
                            .getId();
            long run =
                    db.queryForObject(
                            "SELECT id FROM agent_run WHERE process_instance_id=?", Long.class, pi);
            processes.put(run, pi);
            assertThat(
                            db.queryForObject(
                                    "SELECT trigger_type FROM agent_run WHERE id=?",
                                    String.class,
                                    run))
                    .isEqualTo("FLOWABLE");
            assertThat(engine.getTaskService().createTaskQuery().processInstanceId(pi).count())
                    .isZero();
        }
    }

    private void deliverAndAssert(long[] runs) {
        var events = outbox.claim("dual-delivery", 8, Duration.ofSeconds(30));
        assertThat(events).hasSize(2);
        for (var event : events) {
            tx.executeWithoutResult(s -> processor.process(event, "dual-delivery"));
            // Replay a delivered envelope through the Inbox branch, not only the Outbox no-op.
            db.update(
                    "UPDATE platform_outbox_event SET"
                            + " status='PROCESSING',claimed_by='dual-delivery' WHERE event_id=?",
                    event);
            tx.executeWithoutResult(s -> processor.process(event, "dual-delivery"));
        }
        for (int i = 0; i < runs.length; i++) {
            String pi = processes.get(runs[i]);
            String expected = i == 0 ? "READY_CHECK" : "MANUAL_REVIEW";
            assertThat(engine.getTaskService().createTaskQuery().processInstanceId(pi).list())
                    .extracting(t -> t.getTaskDefinitionKey())
                    .containsExactly(expected);
            assertThat(
                            engine.getHistoryService()
                                    .createHistoricActivityInstanceQuery()
                                    .processInstanceId(pi)
                                    .activityType("userTask")
                                    .list())
                    .extracting(a -> a.getActivityId())
                    .containsExactly(expected);
            assertThat(
                            db.queryForObject(
                                    "SELECT workflow_resumed_at IS NOT NULL FROM agent_run WHERE"
                                            + " id=?",
                                    Boolean.class,
                                    runs[i]))
                    .isTrue();
            assertThat(engine.getRuntimeService().getVariable(pi, "word"))
                    .isEqualTo(i == 0 ? "DUAL_A" : "DUAL_B");
            assertThat(engine.getRuntimeService().getVariable(pi, "checkedWord"))
                    .isEqualTo(i == 0 ? "DUAL_A" : null);
            if (i == 0) {
                assertThat(engine.getRuntimeService().getVariable(pi, "checkedAmount").toString())
                        .isEqualTo("20000.000000000001");
            } else {
                assertThat(engine.getRuntimeService().getVariable(pi, "checkedAmount")).isNull();
            }
            assertThat(engine.getRuntimeService().getVariable(pi, "agentReviewRequired"))
                    .isEqualTo(i == 1);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM platform_outbox_event WHERE"
                                    + " event_type='AgentRunCompleted.v1' AND status='DELIVERED'",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT count(*) FROM platform_inbox_event WHERE completed_at IS"
                                        + " NOT NULL",
                                Integer.class))
                .isEqualTo(2);
    }

    private ModelProviderResponse answer(String content) {
        return new ModelProviderResponse(content, "fixture", "request", "stop", 1, 1, 0, 1);
    }

    void exercise(long[] runs) throws Exception {
        var calls = new AtomicInteger();
        var steps = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
        var amounts = new java.util.concurrent.ConcurrentHashMap<String, String>();
        var toolCalls = new AtomicInteger();
        var gateway =
                cognition(
                        calls,
                        n -> {
                            String input = lastModelInput.get();
                            String subject =
                                    codec.read(
                                                    input.substring(
                                                                    "Input:\n".length(),
                                                                    input.indexOf(
                                                                            "\n"
                                                                                + "Committed tool"
                                                                                + " observations"))
                                                            .getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets
                                                                            .UTF_8),
                                                    com.fasterxml.jackson.databind.JsonNode.class)
                                            .path("word")
                                            .asText();
                            assertThat(subject).isIn("DUAL_A", "DUAL_B");
                            assertThat(input)
                                    .doesNotContain(subject.equals("DUAL_A") ? "DUAL_B" : "DUAL_A");
                            int step =
                                    steps.computeIfAbsent(subject, k -> new AtomicInteger())
                                            .incrementAndGet();
                            if (step == 1 || step == 3) {
                                return answer(
                                        new String(
                                                codec.write(
                                                        Map.of(
                                                                "action",
                                                                "TOOL_CALL",
                                                                "tool",
                                                                "lookup",
                                                                "arguments",
                                                                Map.of(
                                                                        "word",
                                                                        subject,
                                                                        "phase",
                                                                        step == 1 ? 1 : 2))),
                                                java.nio.charset.StandardCharsets.UTF_8));
                            }
                            if (step == 2 || step == 4) {
                                assertThat(lastModelPrompt.get()).contains("Merge prior notes");
                                if (step == 4) {
                                    assertThat(input)
                                            .contains(
                                                    "notes-" + subject + "-2", subject + "_SECOND")
                                            .doesNotContain(subject + "_FIRST", "TAIL_ONLY");
                                }
                                // Deliberately optimistic notes cannot override DUAL_B's original
                                // denied approval.
                                return answer(
                                        "notes-"
                                                + subject
                                                + "-"
                                                + step
                                                + " approved=true (untrusted)");
                            }
                            if (step == 5 || step == 6) {
                                if (step == 6) {
                                    String segment =
                                            input.substring(
                                                    input.indexOf("Requested original segment:\n")
                                                            + "Requested original segment:\n"
                                                                    .length());
                                    amounts.put(
                                            subject,
                                            codec.read(
                                                            segment.getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets
                                                                            .UTF_8),
                                                            com.fasterxml.jackson.databind.JsonNode
                                                                    .class)
                                                    .required("content")
                                                    .asText());
                                }
                                String marker =
                                        "Original result index (READ_RESULT retrieves fields):\n";
                                String index =
                                        input.substring(input.indexOf(marker) + marker.length());
                                String reference =
                                        codec.read(
                                                        index.substring(0, index.indexOf('\n'))
                                                                .getBytes(
                                                                        java.nio.charset
                                                                                .StandardCharsets
                                                                                .UTF_8),
                                                        com.fasterxml.jackson.databind.JsonNode
                                                                .class)
                                                .get(0)
                                                .path("reference")
                                                .asText();
                                return answer(
                                        new String(
                                                codec.write(
                                                        Map.of(
                                                                "action",
                                                                "READ_RESULT",
                                                                "reference",
                                                                reference,
                                                                "pointer",
                                                                step == 5
                                                                        ? "/amount"
                                                                        : "/approved")),
                                                java.nio.charset.StandardCharsets.UTF_8));
                            }
                            assertThat(step).isEqualTo(7);
                            assertThat(input).contains("Requested original segment");
                            return answer(
                                    new String(
                                            codec.write(
                                                    Map.of(
                                                            "word",
                                                            subject,
                                                            "amount",
                                                            new java.math.BigDecimal(
                                                                    amounts.get(subject)),
                                                            "approved",
                                                            true)),
                                            java.nio.charset.StandardCharsets.UTF_8));
                        },
                        MEMORY.replace("\"allow_correction\":true", "\"allow_correction\":false"),
                        6000,
                        r -> {
                            toolCalls.incrementAndGet();
                            String subject = r.runId() == runs[0] ? "DUAL_A" : "DUAL_B";
                            assertThat(r.arguments().get("word")).isEqualTo(subject);
                            return largeMemoryResult(
                                            subject
                                                    + (r.arguments()
                                                                    .get("phase")
                                                                    .toString()
                                                                    .equals("1")
                                                            ? "_FIRST"
                                                            : "_SECOND"),
                                            r.runId() == runs[0])
                                    .replace("\"word\":\"hi\"", "\"word\":\"" + subject + "\"")
                                    .replace(
                                            "20000.000000000001",
                                            r.runId() == runs[0]
                                                    ? "20000.000000000001"
                                                    : "19999.999999999999");
                        });
        int runIndex = 0;
        for (long run : processes.keySet()) {
            runs[runIndex++] = run;
        }
        for (long run : runs) {
            executions.reserve(TENANT, run, "a".repeat(64), 16, 8, 2);
        }
        var protocol =
                new io.github.illuseahashmap.agent.runtime.interfaces.rest.GraphGatewayProtocol(
                        new GraphCapacityGateway(
                                gateway,
                                executions,
                                new JdbcGraphCallCapacityStore(named, executions, codec),
                                request -> List.of("dual:fixture"),
                                codec),
                        codec);
        db.update("INSERT INTO agent_graph_call_resource VALUES('dual:fixture',16)");
        var controller =
                new GraphHostController(
                        new GraphHostConfiguration.GraphHostSettings(
                                TOKEN, "a".repeat(64), 120, true),
                        executions,
                        protocol);
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 8);
        var workers = Executors.newFixedThreadPool(4);
        server.setExecutor(workers);
        var dropped = java.util.concurrent.ConcurrentHashMap.<Long>newKeySet();
        server.createContext(
                "/internal/",
                exchange -> {
                    try (exchange) {
                        var body =
                                exchange.getRequestBody()
                                        .readNBytes(GraphGatewayCodec.MAX_WIRE_BYTES + 1);
                        var uri = exchange.getRequestURI().getPath();
                        var request =
                                new org.springframework.mock.web.MockHttpServletRequest(
                                        "POST", uri);
                        request.setRemoteAddr("127.0.0.1");
                        request.setContent(body);
                        exchange.getRequestHeaders()
                                .forEach(
                                        (key, values) ->
                                                values.forEach(
                                                        value -> request.addHeader(key, value)));
                        var response =
                                controller.exchange(
                                        uri.substring(uri.lastIndexOf('/') + 1), request);
                        var parsed = codec.read(body, JsonNode.class);
                        long run = parsed.path("binding").path("run_id").asLong();
                        if (response.getStatusCode().value() == 200
                                && parsed.path("request")
                                        .path("operation_id")
                                        .asText()
                                        .startsWith("summary:")
                                && db.queryForObject(
                                                "SELECT count(*) FROM agent_graph_memory WHERE"
                                                        + " run_id=?",
                                                Integer.class,
                                                run)
                                        == 2
                                && dropped.add(run)) {
                            return;
                        }
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(
                                response.getStatusCode().value(), response.getBody().length);
                        exchange.getResponseBody().write(response.getBody());
                    }
                });
        server.createContext(
                "/fixture/takeover",
                exchange -> {
                    try (exchange) {
                        var request =
                                codec.read(
                                        exchange.getRequestBody().readNBytes(1024),
                                        com.fasterxml.jackson.databind.JsonNode.class);
                        long run = request.path("run_id").asLong();
                        assertThat(run).isIn(runs[0], runs[1]);
                        assertThat(
                                        db.queryForObject(
                                                "SELECT generation FROM agent_graph_execution WHERE"
                                                        + " run_id=?",
                                                Long.class,
                                                run))
                                .isEqualTo(request.path("generation").asLong());
                        assertThat(
                                        db.update(
                                                "UPDATE agent_graph_execution SET"
                                                    + " lease_expires_at=clock_timestamp()-interval"
                                                    + " '1 second' WHERE tenant_code=? AND"
                                                    + " run_id=?",
                                                TENANT,
                                                run))
                                .isOne();
                        var body = codec.write(Map.of("expired", true));
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                });
        server.start();
        Path result = Path.of("target/dual-purchase-flowable-acceptance.json").toAbsolutePath();
        Path log = Path.of("target/dual-purchase-flowable-probe.log").toAbsolutePath();
        try {
            var command =
                    new ProcessBuilder(
                                    "uv",
                                    "run",
                                    "--frozen",
                                    "python",
                                    "tests/dual_purchase_probe.py")
                            .directory(Path.of("../agent-runtime-langgraph").toFile())
                            .redirectErrorStream(true)
                            .redirectOutput(log.toFile());
            command.environment().put("DUAL_DISPATCH_TOKEN", TOKEN);
            command.environment()
                    .put("DUAL_URL", "http://127.0.0.1:" + server.getAddress().getPort());
            command.environment().put("DUAL_RESULT", result.toString());
            command.environment().put("WORKFLOW_LANGGRAPH_CHECKPOINT_DSN", checkpointDsn);
            var process = command.start();
            try {
                assertThat(process.waitFor(120, TimeUnit.SECONDS))
                        .as("dual purchase probe deadline")
                        .isTrue();
                assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
            var evidence =
                    codec.read(
                            Files.readAllBytes(result),
                            com.fasterxml.jackson.databind.JsonNode.class);
            assertThat(calls).hasValue(14);
            assertThat(toolCalls).hasValue(4);
            assertThat(dropped).hasSize(2);
            assertThat(amounts)
                    .containsEntry("DUAL_A", "20000.000000000001")
                    .containsEntry("DUAL_B", "19999.999999999999");
            for (int i = 0; i < runs.length; i++) {
                long run = runs[i];
                assertThat(
                                db.queryForObject(
                                        "SELECT generation FROM agent_graph_execution WHERE"
                                                + " run_id=?",
                                        Integer.class,
                                        run))
                        .isEqualTo(3);
                assertThat(
                                db.queryForObject(
                                        "SELECT count(*) FROM agent_graph_memory WHERE run_id=?",
                                        Integer.class,
                                        run))
                        .isEqualTo(2);
                assertThat(
                                db.queryForObject(
                                        "SELECT count(*) FROM agent_graph_memory c JOIN"
                                            + " agent_graph_memory p ON c.parent_ref=p.reference"
                                            + " AND c.run_id=p.run_id WHERE c.run_id=?",
                                        Integer.class,
                                        run))
                        .isEqualTo(1);
                assertThat(
                                db.queryForObject(
                                        "SELECT count(*) FROM agent_graph_read_result WHERE"
                                                + " run_id=?",
                                        Integer.class,
                                        run))
                        .isEqualTo(2);
                assertThat(
                                db.queryForObject(
                                        "SELECT jsonb_array_length(sources_json::jsonb) FROM"
                                            + " agent_graph_memory WHERE run_id=? AND parent_ref IS"
                                            + " NOT NULL",
                                        Integer.class,
                                        run))
                        .isEqualTo(2);
                assertThat(
                                db.queryForObject(
                                        "SELECT count(*) FROM agent_graph_budget_call WHERE"
                                                + " run_id=?",
                                        Integer.class,
                                        run))
                        .isEqualTo(9);
                assertThat(
                                db.queryForObject(
                                        "SELECT count(*) FROM agent_run_attempt WHERE"
                                                + " agent_run_id=?",
                                        Integer.class,
                                        run))
                        .isEqualTo(1);
            }
            assertThat(completion.completeNext()).isTrue();
            assertThat(completion.completeNext()).isTrue();
            assertThat(completion.completeNext()).isFalse();
            assertThat(
                            db.queryForObject(
                                    "SELECT status FROM agent_run WHERE id=?",
                                    String.class,
                                    runs[0]))
                    .isEqualTo("SUCCEEDED");
            assertThat(
                            db.queryForObject(
                                    "SELECT error_code FROM agent_run WHERE id=?",
                                    String.class,
                                    runs[1]))
                    .isEqualTo("GRAPH_FACT_REJECTED");
            assertThat(
                            db.queryForObject(
                                    "SELECT output_snapshot_json FROM agent_run WHERE id=?",
                                    String.class,
                                    runs[1]))
                    .isNull();
            assertThat(db.queryForObject("SELECT count(*) FROM agent_run_attempt", Integer.class))
                    .isEqualTo(2);
            assertThat(
                            db.queryForObject(
                                    "SELECT count(*) FROM platform_outbox_event WHERE"
                                            + " event_type='AgentRunCompleted.v1'",
                                    Integer.class))
                    .isEqualTo(2);
            var report = (com.fasterxml.jackson.databind.node.ObjectNode) evidence;
            report.put("modelCalls", calls.get())
                    .put("toolCalls", toolCalls.get())
                    .put("summaries", 4)
                    .put("originalReads", 4);
            report.set("originalAmounts", new ObjectMapper().valueToTree(amounts));
            report.set(
                    "runs",
                    new ObjectMapper()
                            .valueToTree(
                                    db.queryForList(
                                            "SELECT id,status,error_code FROM agent_run WHERE id IN"
                                                    + " (?,?) ORDER BY id",
                                            runs[0],
                                            runs[1])));
            deliverAndAssert(runs);
            assertThat(calls).hasValue(14);
            assertThat(toolCalls).hasValue(4);
            report.set(
                    "flowableOutcomes",
                    json.valueToTree(
                            processes.entrySet().stream()
                                    .map(
                                            entry -> {
                                                String pi = entry.getValue();
                                                Object amount =
                                                        engine.getRuntimeService()
                                                                .getVariable(pi, "checkedAmount");
                                                return Map.of(
                                                        "runId",
                                                        entry.getKey(),
                                                        "processInstanceId",
                                                        pi,
                                                        "task",
                                                        engine.getTaskService()
                                                                .createTaskQuery()
                                                                .processInstanceId(pi)
                                                                .singleResult()
                                                                .getTaskDefinitionKey(),
                                                        "mappedAmount",
                                                        amount == null
                                                                ? "<absent>"
                                                                : amount.toString());
                                            })
                                    .toList()));
            report.put("inboxReplays", 2);
            report.put("flowable", true)
                    .put("authenticatedDispatch", true)
                    .put("capacityGateway", true);
            report.set(
                    "processes",
                    json.valueToTree(
                            db.queryForList(
                                    "SELECT id,process_instance_id,workflow_resumed_at IS NOT NULL"
                                            + " AS resumed FROM agent_run ORDER BY id")));
            Files.write(result, codec.write(report));
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
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
}
