package io.github.illuseahashmap.workflow.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.illuseahashmap.agent.definition.domain.*;
import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.application.port.*;
import io.github.illuseahashmap.agent.provider.domain.*;
import io.github.illuseahashmap.agent.runtime.application.*;
import io.github.illuseahashmap.agent.runtime.application.checkpoint.CheckpointContextPolicy;
import io.github.illuseahashmap.agent.runtime.application.impl.*;
import io.github.illuseahashmap.agent.runtime.domain.AgentRunTriggerType;
import io.github.illuseahashmap.workflow.process.application.*;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.FlowableAgentWorkflowExecutionAdapter;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.AgentTaskExecutionListener;
import org.springframework.transaction.support.TransactionTemplate;
import io.github.illuseahashmap.agent.runtime.application.port.*;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.*;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real receipts, durable checkpoints, cold executor and BPMN gateway; only the external model and tool are fixtures. */
class ProcurementTaskMemoryIntegrationTest {
    private static final String TENANT = "default", TOOL = "purchase_lookup";
    private static final String SCHEMA = """
            {"type":"object","required":["decision","source"],"properties":{"decision":{"type":"string"},"source":{"type":"string"}},
             "x-workflow-decision":{"version":"1","tool":"purchase_lookup","subjectField":"orderId","factField":"admission",
                "outputField":"decision","referenceField":"source","cases":{"UNKNOWN":"VERIFY","UNADMITTED":"COMMITTEE"}}}
            """;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private JdbcTemplate db;
    private NamedParameterJdbcTemplate named;
    private AgentDefinitionVersion version;
    private AgentProvider provider;
    private TransactionTemplate tx;
    private record Order(String id, String status, String branch, long run, long attempt, String process) {
        String ref(int step) { return run + ":tool:" + step + ":" + TOOL; }
    }

    @Test
    void coldRecoveryPreservesBothBranchesAndRejectsForeignReceiptsEvenAfterJsonRepair() throws Exception {
        PostgreSQLContainer<?> container = null;
        String url = System.getenv("WORKFLOW_TEST_DATABASE_URL");
        String user = System.getenv("WORKFLOW_TEST_DATABASE_USER"), password = System.getenv("WORKFLOW_TEST_DATABASE_PASSWORD");
        if (url == null || url.isBlank()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable(),
                    "An isolated PostgreSQL or Docker is required");
            container = new PostgreSQLContainer<>("postgres:17-alpine"); container.start();
            url = container.getJdbcUrl(); user = container.getUsername(); password = container.getPassword();
        }
        try {
            Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration/platform").load().migrate();
            var ds = new DriverManagerDataSource(url,user,password);
            db = new JdbcTemplate(ds); named = new NamedParameterJdbcTemplate(ds);
            var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
            tx = new TransactionTemplate(manager);
            var cfg = new org.flowable.spring.SpringProcessEngineConfiguration();
            cfg.setTransactionManager(manager);
            cfg.setDataSource(ds).setDatabaseSchemaUpdate("true").setAsyncExecutorActivate(false);
            var engine = cfg.buildProcessEngine();
            try {
                seed();
                String key = "purchase_" + UUID.randomUUID().toString().replace("-","");
                engine.getRepositoryService().createDeployment().addString(key+".bpmn20.xml", BPMN.formatted(key)).deploy();
                var runtime = engine.getRuntimeService();
                var a = order("A","UNKNOWN","VERIFY",runtime.startProcessInstanceByKey(key).getId());
                var b = order("B","UNADMITTED","COMMITTEE",runtime.startProcessInstanceByKey(key).getId());
                String first = interrupt(a), second = interrupt(b);
                assertThat(calls.get()).isEqualTo(4);

                var audits = new JdbcAgentToolGovernanceRepository(named);
                var cached = audits.findSucceededByRun(TENANT,a.run,Set.of(a.ref(1),a.ref(2)));
                assertThat(cached).hasSize(2).allMatch(row -> row.output() == null);

                var revoked = model();
                when(revoked.invoke(any())).thenAnswer(inv -> {
                    db.update("UPDATE agent_tool_tenant_grant SET enabled=false WHERE tenant_code=? AND tool_code=?",TENANT,TOOL);
                    return response("invalid JSON");
                });
                try {
                    assertThatThrownBy(() -> run(revoked,a,first,null)).hasMessageContaining("no longer authorized");
                    verify(revoked).invoke(any());
                } finally {
                    db.update("UPDATE agent_tool_tenant_grant SET enabled=true WHERE tenant_code=? AND tool_code=?",TENANT,TOOL);
                }

                var blocked = model();
                assertThatThrownBy(() -> run(blocked,a,second,null)).hasMessageContaining("ownership");
                ObjectNode damaged = (ObjectNode)json.readTree(first);
                ObjectNode state = (ObjectNode)json.readTree(damaged.path("context").asText());
                ((ObjectNode)state.path("receipts").get(0)).put("reference", b.ref(1));
                damaged.put("context",state.toString()).put("contextDigest",new CheckpointContextPolicy(40000).digest(state.toString()));
                assertThatThrownBy(() -> run(blocked,a,damaged.toString(),null)).hasMessageContaining("does not belong");
                verify(blocked,never()).invoke(any());

                var badRead = model();
                when(badRead.invoke(any())).thenReturn(response(json.writeValueAsString(Map.of(
                        "action","READ_RESULT","reference",b.ref(1),"pointer","/delayed"))));
                assertThatThrownBy(() -> run(badRead,a,first,null)).hasMessageContaining("Unknown result reference");
                verify(badRead).invoke(any());

                for (var order : List.of(a,b)) {
                    String saved = order == a ? first : second;
                    // The repair must not launder either the wrong branch or a same-tenant foreign citation.
                    assertThatThrownBy(() -> finish(order,saved,order == a ? b.branch : a.branch,order.ref(2)))
                            .hasMessageContaining("authoritative");
                    assertThatThrownBy(() -> finish(order,saved,order.branch,order == a ? b.ref(2) : a.ref(2)))
                            .hasMessageContaining("authoritative");
                    var result = finish(order,saved,order.branch,order.ref(2));
                    assertThat(result.steps()).extracting(AgentExecutor.StepResult::stepType)
                            .containsExactly("OUTPUT_REPAIR","VALIDATION");
                    var wait = runtime.createExecutionQuery().processInstanceId(order.process).activityId("awaitResult").singleResult();
                    runtime.trigger(wait.getId(), Map.of("decision",json.readTree(result.modelResponse().content()).get("decision").asText()));
                    assertThat(engine.getTaskService().createTaskQuery().processInstanceId(order.process)
                            .singleResult().getTaskDefinitionKey()).isEqualTo(order.branch);
                }
                assertThat(calls.get()).isEqualTo(4);
                workerFaults(engine,key,"A","VERIFY");
                workerFaults(engine,key,"B","COMMITTEE");
            } finally { engine.close(); }
        } finally { if (container != null) container.stop(); }
    }

    /** Deliberately not a RuntimeException: a dead worker cannot run its failure handler. */
    private static final class PowerLoss extends Error {}

    private void workerFaults(org.flowable.engine.ProcessEngine engine, String key, String id, String branch) throws Exception {
        var runtime = engine.getRuntimeService();
        var process = runtime.startProcessInstanceByKey(key);
        var wait = runtime.createExecutionQuery().processInstanceId(process.getId()).activityId("awaitResult").singleResult();
        String activation = UUID.randomUUID().toString();
        runtime.setVariableLocal(wait.getId(), AgentTaskExecutionListener.ACTIVATION_VARIABLE, activation);
        var repo = new JdbcAgentRunExecutionRepository(named);
        var now = Instant.now();
        long runId = repo.insertQueued(new AgentRunExecutionRepository.Submission(TENANT,version.id(),activation,
                AgentRunTriggerType.FLOWABLE, json.writeValueAsString(Map.of("input",Map.of("orderId",id,"amount",25000))),
                "buyer",now.plusSeconds(120),now,process.getId(),wait.getId(),"awaitResult",activation,
                "{\"decision\":\"decision\"}","HOLD_FOR_OPERATIONS",120)).id();
        String ref = runId + ":tool:2:" + TOOL;
        int callsBefore = calls.get();
        var firstModel = model();
        var turn = new AtomicInteger();
        when(firstModel.invoke(any())).thenAnswer(inv -> switch(turn.incrementAndGet()) {
            case 1 -> response("{\"schemaVersion\":\"1\",\"mode\":\"DIRECT\",\"reason\":\"verify\",\"requestedTools\":[\"purchase_lookup\"],\"steps\":[\"lookup\"]}");
            case 2,4 -> response(json.writeValueAsString(Map.of("action","TOOL_CALL","tool",TOOL,"arguments",Map.of("orderId",id))));
            // Long notes make the subsequent 2048 -> 512 budget change observable.
            case 3,5 -> response("n".repeat(900));
            default -> throw new AssertionError("Unexpected first-attempt call");
        });
        var faultRepo = spy(new JdbcAgentRunExecutionRepository(named));
        var toolSteps = new AtomicInteger();
        doAnswer(inv -> {
            if ("TOOL_CALL".equals(inv.getArgument(5)) && toolSteps.incrementAndGet() == 2) throw new PowerLoss();
            return inv.callRealMethod();
        }).when(faultRepo).insertProgressIfCurrentLeaseValid(anyString(), anyLong(), anyLong(), anyString(), anyInt(),
                anyString(), anyString(), nullable(String.class), anyString(), anyString(), any());
        var publisher = new AgentRunEventPublisherAdapter(
                new io.github.illuseahashmap.workflow.event.JdbcReliableEventPublisher(named,List.of()),json);
        assertThatThrownBy(() -> worker(faultRepo,firstModel,2048,publisher).executeNext()).isInstanceOf(PowerLoss.class);
        assertThat(calls.get() - callsBefore).isEqualTo(2);
        assertThat(new JdbcAgentToolGovernanceRepository(named).findByIdempotencyKey(TENANT,TOOL,ref).orElseThrow().status()).isEqualTo("SUCCEEDED");
        var last = json.readTree(repo.findLatestCheckpoint(TENANT,runId).orElseThrow().snapshotJson());
        assertThat(last.path("logicalStepId").asText()).isEqualTo("tool-intent:2");
        assertThat(json.readTree(last.path("context").textValue()).path("notes").textValue()).hasSize(900);

        var answering = model();
        var delivered = response(json.writeValueAsString(Map.of("decision",branch,"source",ref)));
        var phases = new AtomicInteger();
        when(answering.invoke(any())).thenAnswer(inv -> {
            ModelProviderRequest r = inv.getArgument(0);
            assertThat(r.userInput()).contains(ref).doesNotContain("n".repeat(513));
            return switch(phases.incrementAndGet()) {
                case 1 -> response("broken final JSON");
                case 2 -> delivered;
                default -> throw new AssertionError("Model was asked to replan, repeat a tool or repeat an answer");
            };
        });
        // Lose the worker after validation, inside the Run + Outbox transaction, before commit.
        AgentRunEventPublisher failCommit = (run,snapshot) -> {
            publisher.completed(run,snapshot);
            throw new PowerLoss();
        };
        var validationRepo = repo;
        if ("B".equals(id)) {
            validationRepo = spy(new JdbcAgentRunExecutionRepository(named));
            doAnswer(inv -> { throw new PowerLoss(); }).when(validationRepo).insertProgressIfCurrentLeaseValid(
                    anyString(),anyLong(),anyLong(),anyString(),anyInt(),eq("VALIDATION"),anyString(),nullable(String.class),
                    anyString(),anyString(),any());
        }
        var recovering = worker(validationRepo, answering,512,failCommit);
        expireLease(runId,recovering);
        assertThatThrownBy(recovering::executeNext).isInstanceOf(PowerLoss.class);
        assertThat(phases.get()).isEqualTo(2);
        if ("B".equals(id)) {
            // The answer was checked, but even the VALIDATION checkpoint write was lost.
            assertThat(json.readTree(repo.findLatestCheckpoint(TENANT,runId).orElseThrow().snapshotJson())
                    .path("logicalStepId").textValue()).isEqualTo("output-repair");
            var noAnswer = model();
            var replayCandidate = worker(repo,noAnswer,256,failCommit);
            expireLease(runId,replayCandidate);
            assertThatThrownBy(replayCandidate::executeNext).isInstanceOf(PowerLoss.class);
            verify(noAnswer,never()).invoke(any());
        }
        String saved = repo.findLatestCheckpoint(TENANT,runId).orElseThrow().snapshotJson();
        var validated = json.readTree(saved);
        assertThat(validated.path("logicalStepId").asText()).isEqualTo("validation");
        assertThat(json.treeToValue(json.readTree(validated.path("context").textValue()).path("candidate").path("response"), ModelProviderResponse.class))
                .isEqualTo(delivered);
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=?",String.class,runId)).isEqualTo("RUNNING");
        assertThat(db.queryForObject("SELECT count(*) FROM platform_outbox_event WHERE aggregate_id=? AND event_type=?",
                Integer.class,Long.toString(runId),AgentCompletionEventStore.EVENT_TYPE)).isZero();

        var forbidden = model();
        // Revoke between validation and finalization: even a saved answer cannot override current grants.
        db.update("UPDATE agent_tool_tenant_grant SET enabled=false WHERE tenant_code=? AND tool_code=?",TENANT,TOOL);
        try {
            var order = new Order(id,"",branch,runId,0,process.getId());
            assertThatThrownBy(() -> run(forbidden,order,saved,null)).hasMessageContaining("no longer authorized");
        } finally {
            db.update("UPDATE agent_tool_tenant_grant SET enabled=true WHERE tenant_code=? AND tool_code=?",TENANT,TOOL);
        }
        var lastWorker = worker(repo,forbidden,256,publisher);
        expireLease(runId,lastWorker);
        assertThat(lastWorker.executeNext()).isTrue();
        verify(forbidden,never()).invoke(any());
        assertThat(calls.get() - callsBefore).isEqualTo(2);
        assertThat(db.queryForObject("SELECT status FROM agent_run WHERE id=?",String.class,runId)).isEqualTo("SUCCEEDED");
        assertThat(db.queryForObject("SELECT output_snapshot_json::jsonb->>'content' FROM agent_run WHERE id=?",String.class,runId))
                .isEqualTo(delivered.content());

        var outbox = new AgentCompletionEventStore(named,tx);
        var ids = outbox.claim("completion-test",1,Duration.ofSeconds(30));
        assertThat(ids).hasSize(1);
        var processor = new AgentFlowableCompletionProcessor(named,new AgentCompletionRecoveryService(
                new JdbcAgentCompletionRunAdapter(named),new FlowableAgentWorkflowExecutionAdapter(named,runtime),
                new AgentOutputMappingResolver(json)),json);
        tx.executeWithoutResult(status -> processor.process(ids.getFirst(),"completion-test"));
        tx.executeWithoutResult(status -> processor.process(ids.getFirst(),"completion-test"));
        assertThat(engine.getTaskService().createTaskQuery().processInstanceId(process.getId()).singleResult()
                .getTaskDefinitionKey()).isEqualTo(branch);
        assertThat(db.queryForObject("SELECT workflow_resumed_at IS NOT NULL FROM agent_run WHERE id=?",Boolean.class,runId)).isTrue();
    }

    private void expireLease(long run, AgentRunWorkerServiceImpl successor) {
        db.update("UPDATE agent_run SET lease_expires_at=now()-interval '1 second' WHERE id=?",run);
        assertThat(successor.recoverExpiredRuns()).isOne();
    }

    private AgentRunWorkerServiceImpl worker(JdbcAgentRunExecutionRepository repo, ModelProviderPort model,
                                             int budget, AgentRunEventPublisher publisher) {
        var versions = mock(AgentDefinitionVersionRepository.class);
        var providers = mock(AgentProviderRepository.class);
        when(versions.findByVersionId(TENANT,version.id())).thenReturn(Optional.of(version));
        when(providers.findById(TENANT,provider.id())).thenReturn(Optional.of(provider));
        return new AgentRunWorkerServiceImpl(repo,versions,providers,new AgentExecutorRegistry(List.of(executor(model,budget))),
                json,tx,"fault-test",30,8,64,publisher,AgentRunLeaseHeartbeat.NOOP,new DefaultAgentResultPolicy(json),
                AgentRuntimeMetrics.NOOP, ExponentialJitterRetryBackoffPolicy.defaults(new Random(23)),
                new AgentRecoveryPolicy(),new AgentFailureMapper());
    }
    private void seed() {
        long providerId = db.queryForObject("INSERT INTO agent_provider(tenant_code,provider_code,provider_name,provider_type) VALUES(?,?,'Test','MOCK') RETURNING id",Long.class,TENANT,UUID.randomUUID().toString());
        provider = new AgentProvider(providerId,TENANT,"fixture","Fixture",AgentProviderType.MOCK,
                "http://localhost","fixture",true,false,null,OffsetDateTime.now(),OffsetDateTime.now());
        long def = db.queryForObject("INSERT INTO agent_definition(tenant_code,agent_code,agent_name) VALUES(?,?,'Purchase') RETURNING id",
                Long.class,TENANT,UUID.randomUUID().toString());
        long id = db.queryForObject("INSERT INTO agent_definition_version(tenant_code,definition_id,version,status,output_schema) VALUES(?,?,1,'PUBLISHED',?) RETURNING id",
                Long.class,TENANT,def,SCHEMA);
        version = new AgentDefinitionVersion(id,TENANT,def,1,AgentVersionStatus.PUBLISHED,AgentExecutionMode.PLATFORM_AGENT,
                providerId,"fixture","Test purchase admission",60,AgentFailurePolicy.FAIL_PROCESS,null,SCHEMA,"user","user",
                OffsetDateTime.now(),OffsetDateTime.now(),OffsetDateTime.now(),"[\"purchase_lookup\"]");
        db.update("INSERT INTO agent_tool_definition(tool_code,tool_name,input_schema) VALUES(?,?,'{\"type\":\"object\"}') ON CONFLICT DO NOTHING",TOOL,TOOL);
        db.update("INSERT INTO agent_tool_tenant_grant(tenant_code,tool_code) VALUES(?,?) ON CONFLICT DO NOTHING",TENANT,TOOL);
    }
    private Order order(String id,String status,String branch,String process) {
        long run = db.queryForObject("INSERT INTO agent_run(tenant_code,idempotency_key,agent_version_id,status,process_instance_id,deadline_at) VALUES(?,?,?,'RUNNING',?,now()+interval '5 minutes') RETURNING id",
                Long.class,TENANT,process,version.id(),process);
        long attempt = db.queryForObject("INSERT INTO agent_run_attempt(tenant_code,agent_run_id,attempt_no,status) VALUES(?,?,1,'RUNNING') RETURNING id",Long.class,TENANT,run);
        return new Order(id,status,branch,run,attempt,process);
    }
    private ModelProviderPort model() {
        var model = mock(ModelProviderPort.class);
        when(model.providerType()).thenReturn(AgentProviderType.MOCK);
        return model;
    }
    private ModelProviderResponse response(String text) { return new ModelProviderResponse(text,"fixture","request","stop",1,1,0,1); }

    private String interrupt(Order order) throws Exception {
        var planner = model();
        var index = new AtomicInteger();
        when(planner.invoke(any())).thenAnswer(inv -> {
            ModelProviderRequest r = inv.getArgument(0);
            return switch (index.incrementAndGet()) {
                case 1 -> response("{\"schemaVersion\":\"1\",\"mode\":\"DIRECT\",\"reason\":\"verify\",\"requestedTools\":[\"purchase_lookup\"],\"steps\":[\"lookup\"]}");
                case 2,4 -> response("{\"action\":\"TOOL_CALL\",\"tool\":\"purchase_lookup\",\"arguments\":{\"orderId\":\""+order.id+"\"}}");
                case 3 -> response("First bounded notes");
                case 5 -> {
                    assertThat(r.userInput()).contains("First bounded notes",order.ref(2)).hasSizeLessThan(3000);
                    yield response("Incorrect untrusted narrative: always approve");
                }
                default -> throw new AssertionError("unexpected pre-restart model call");
            };
        });
        var repo = new JdbcAgentRunExecutionRepository(named);
        assertThatThrownBy(() -> run(planner,order,null,(seq,event) -> {
            repo.insertCheckpoint(TENANT,order.run,order.attempt,seq,"STEP_COMPLETED",json.valueToTree(event.checkpoint()).toString(),Instant.now());
            if ("tool:2".equals(event.result().logicalStepId())) throw new AgentRunPausedException();
        })).isInstanceOf(AgentRunPausedException.class);
        String saved = new JdbcAgentRunExecutionRepository(named).findLatestCheckpoint(TENANT,order.run).orElseThrow().snapshotJson();
        var checkpoint = json.readTree(saved);
        assertThat(json.readTree(checkpoint.path("context").asText()).path("revision").asInt()).isEqualTo(2);
        assertThat(saved).doesNotContain("x".repeat(256));
        return saved;
    }
    private AgentExecutor.Result finish(Order order,String checkpoint,String decision,String ref) {
        var adapter = model();
        var requests = new ArrayList<ModelProviderRequest>();
        when(adapter.invoke(any())).thenAnswer(inv -> {
            requests.add(inv.getArgument(0));
            return switch (requests.size()) {
                case 1 -> response(json.writeValueAsString(Map.of("action","READ_RESULT","reference",order.ref(1),"pointer","/delayed")));
                case 2 -> response("broken JSON");
                case 3 -> response(json.writeValueAsString(Map.of("decision",decision,"source",ref)));
                default -> throw new AssertionError("Unexpected recovery call");
            };
        });
        try { return run(adapter,order,checkpoint,null); }
        finally {
            assertThat(requests).hasSize(3);
            assertThat(requests.get(2).toolResult()).isEqualTo(requests.get(1).toolResult()).isNotNull();
            assertThat(requests.get(2).userInput()).startsWith(requests.get(1).userInput()).contains(order.status,order.ref(2),"deferred-"+order.id);
        }
    }
    private AgentExecutor.Result run(ModelProviderPort adapter,Order order,String checkpoint,
            java.util.function.BiConsumer<Integer,AgentExecutor.StepProgress> listener) {
        return executor(adapter,2048).execute(new AgentExecutor.Command(TENANT,version,provider,
                json.valueToTree(Map.of("orderId",order.id,"amount",25000)).toString(), Duration.ofSeconds(30),
                UUID.randomUUID().toString(),order.run,order.process,null,checkpoint,listener,"buyer"));
    }
    private PlatformAgentExecutor executor(ModelProviderPort adapter,int budget) {
        var jdbc = new JdbcAgentToolGovernanceRepository(named);
        AgentTool tool = new AgentTool() {
            public String name() { return TOOL; }
            public Result execute(Request r) {
                calls.incrementAndGet();
                String id = (String)r.arguments().get("orderId");
                return new Result(json.valueToTree(Map.of("orderId",id,"admission",id.equals("A")?"UNKNOWN":"UNADMITTED",
                        "delayed","deferred-"+id,"background","x".repeat(3500))).toString(),r.idempotencyKey());
            }
        };
        var validator = new AgentOutputSchemaValidator(json);
        return new PlatformAgentExecutor(mock(AgentCredentialResolver.class),new ModelProviderRegistry(List.of(adapter)),
                validator,new AgentToolRegistry(List.of(tool),jdbc,jdbc,validator,json),json,
                new io.github.illuseahashmap.agent.runtime.application.routing.AgentRouteDecisionParser(json),
                new CheckpointContextPolicy(40_000,budget),4);
    }
    private static final String BPMN = """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" targetNamespace="test">
        <process id="%s" isExecutable="true"><startEvent id="start"/><sequenceFlow id="a" sourceRef="start" targetRef="awaitResult"/>
        <receiveTask id="awaitResult"/><sequenceFlow id="b" sourceRef="awaitResult" targetRef="route"/><exclusiveGateway id="route"/>
        <sequenceFlow id="c" sourceRef="route" targetRef="VERIFY"><conditionExpression xsi:type="tFormalExpression">${decision=='VERIFY'}</conditionExpression></sequenceFlow>
        <sequenceFlow id="d" sourceRef="route" targetRef="COMMITTEE"><conditionExpression xsi:type="tFormalExpression">${decision=='COMMITTEE'}</conditionExpression></sequenceFlow>
        <userTask id="VERIFY"/><userTask id="COMMITTEE"/></process></definitions>
        """;
}
