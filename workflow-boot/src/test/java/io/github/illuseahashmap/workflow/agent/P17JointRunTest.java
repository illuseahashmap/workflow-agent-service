package io.github.illuseahashmap.workflow.agent;

import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.application.port.*;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.agent.provider.infrastructure.openai.*;
import io.github.illuseahashmap.agent.runtime.application.*;
import io.github.illuseahashmap.agent.runtime.application.impl.*;
import io.github.illuseahashmap.agent.runtime.application.port.*;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.JdbcAgentRunExecutionRepository;
import io.github.illuseahashmap.workflow.event.JdbcReliableEventPublisher;
import io.github.illuseahashmap.workflow.process.application.*;
import io.github.illuseahashmap.workflow.process.infrastructure.flowable.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static io.github.illuseahashmap.workflow.agent.P17Pilot.*;

/** Read-only joint-routing pilot. Scripted runs check instrumentation, never live model quality. */
class P17JointRunTest {
    @Test void scriptedRunsExercisePublishedBindingsAndOutbox() throws Exception { run(false); }

    @Test @EnabledIfSystemProperty(named="workflow.rag.live-p17", matches="true")
    void liveRunsUseTheSamePublishedFixture() throws Exception {
        new ProviderEndpointValidator(false,false).validate(URI.create(ARK));
        run(true);
    }

    private void run(boolean live) throws Exception {
        P17RoutingFixtureTest.withDatabase((url,user,password)->{
            Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration/platform").load().migrate();
            var ds=new DriverManagerDataSource(url,user,password);
            var manager=new DataSourceTransactionManager(ds);
            var tx=new TransactionTemplate(manager);
            var named=new NamedParameterJdbcTemplate(ds);
            var db=named.getJdbcTemplate();
            var p=new P17Pilot(named,tx,live);
            var repo=new JdbcAgentRunExecutionRepository(named);
            var publisher=new AgentRunEventPublisherAdapter(new JdbcReliableEventPublisher(named,List.of()),JSON);
            var submission=new AgentRunSubmissionServiceImpl(p.definitions,p.versions,p.providers,repo,p.tenant,p.buyer,JSON,publisher);
            var cfg=new org.flowable.spring.SpringProcessEngineConfiguration();
            cfg.setTransactionManager(manager);
            cfg.setDataSource(ds).setDatabaseSchemaUpdate("true").setAsyncExecutorActivate(false);
            cfg.setBeans(Map.of("agentTaskExecutionListener",new AgentTaskExecutionListener(new AgentRunGatewayAdapter(submission),p.tenant,new AgentInputMappingResolver(JSON))));
            var engine=cfg.buildProcessEngine();
            try {
                var runtime=engine.getRuntimeService();
                var xml=Files.readString(DIR.resolve("order-readiness.bpmn20.xml"));
                var bpmnDigests=new HashMap<Long,String>();
                var batch=UUID.fromString(System.getProperty("workflow.p17.batch-id",UUID.randomUUID().toString()));
                var dir=Files.createDirectory(Files.createDirectories(Path.of("target","p17-runs")).resolve(batch.toString()));
                var processor=new AgentFlowableCompletionProcessor(named,new AgentCompletionRecoveryService(new JdbcAgentCompletionRunAdapter(named),
                        new FlowableAgentWorkflowExecutionAdapter(named,runtime),new AgentOutputMappingResolver(JSON)),JSON);
                for(long version:List.of(p.policyVersion,p.jointVersion)){
                    String rendered=xml.replace("agentVersionId=\"1\"","agentVersionId=\""+version+"\"").replace("p17_order_readiness_v1","p17_"+version);
                    bpmnDigests.put(version,hash(rendered.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    engine.getRepositoryService().createDeployment().tenantId(TENANT).addString("p17.bpmn20.xml",rendered).deploy();
                }
                for(var c:p.gold.path("cases")){
                    String id=c.path("id").asText();
                    boolean timeout=id.equals("P17-TIMEOUT"), revoked=id.equals("P17-REVOKED");
                    boolean joint=!id.equals("B08");
                    long version=joint?p.jointVersion:p.policyVersion;
                    p.records.mode=c.path("live").asText();
                    p.records.ledger.clear();
                    String pi=runtime.startProcessInstanceByKeyAndTenantId("p17_"+version,Map.of("orderId","P-17",
                            "question",c.path("query").asText(p.gold.path("query").asText())),TENANT).getId();
                    long runId=db.queryForObject("SELECT id FROM agent_run WHERE tenant_code=? AND process_instance_id=? AND requested_by=? AND agent_version_id=?",
                            Long.class,TENANT,pi,BUYER,version);
                    p.observation=new P17ScoreTrace(db,dir,id,runId,pi);
                    ModelProviderPort model=live?new LangChain4jOpenAiModelProviderAdapter(JSON,new ProviderEndpointValidator(false,false),1048576):new Scripted(p,joint);
                    var observedModel=new ModelProviderPort(){
                        public AgentProviderType providerType(){return model.providerType();}
                        public ModelProviderCapabilities capabilities(String url){return model.capabilities(url);}
                        public ModelProviderResponse invoke(ModelProviderRequest request){
                            assertThat(p.revokedLocators).as("No model request, including summarization, after revocation").isEmpty();
                            return model.invoke(request);
                        }
                    };
                    var executor=new PlatformAgentExecutor(p.credentials,new ModelProviderRegistry(List.of(observedModel)),p.validator,p.registry,JSON,6);
                    var worker=new AgentRunWorkerServiceImpl(repo,p.versions,p.providers,new AgentExecutorRegistry(List.of(executor)),JSON,tx,
                            "pilot-worker",180,2,1,publisher,AgentRunLeaseHeartbeat.NOOP,new DefaultAgentResultPolicy(JSON),AgentRuntimeMetrics.NOOP,
                            attempt->Duration.ZERO,new AgentRecoveryPolicy(),new AgentFailureMapper());
                    assertThat(worker.executeNext()).isTrue();
                    // A claimed retry re-enters through the worker and its persisted tool-intent checkpoint.
                    if(timeout)worker.executeNext();
                    p.observation.completed();
                    var outbox=new AgentCompletionEventStore(named,tx);
                    for(var event:outbox.claim("pilot-completion",1,Duration.ofSeconds(30))){
                        tx.executeWithoutResult(s->processor.process(event,"pilot-completion"));
                    }
                    Thread.sleep(p.gold.path("observeAfterMs").asLong());
                    var tasks=engine.getTaskService().createTaskQuery().processInstanceId(pi).list().stream().map(t->t.getTaskDefinitionKey()).toList();
                    var activities=engine.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(pi).activityType("userTask").list();
                    var history=activities.stream().map(h->h.getActivityId()).toList();
                    p.observation.finish(tasks,history,p.records.ledger,activities.stream().mapToLong(h->h.getStartTime().getTime()).min().orElseThrow(),System.currentTimeMillis());
                    var report=new LinkedHashMap<String,Object>();
                    report.put("case",id);report.put("model",p.model);report.put("source",live?"LIVE_MODEL":"SCRIPTED_TRANSPORT");
                    report.put("tenant",TENANT);report.put("principal",BUYER);report.put("process",pi);report.put("run",runId);report.put("version",version);
                    report.put("profile",p.profile);report.put("index",p.index);report.put("mcpSnapshot",joint?p.snapshotId:null);
                    report.put("bpmnSha256",bpmnDigests.get(version));report.put("policySha256Lf",p.gold.path("policySha256Lf").asText());
                    report.put("catalog",joint?p.catalogId:null);report.put("promptSha256",hash(Files.readAllBytes(DIR.resolve("e1-prompt.txt"))));
                    var run=db.queryForMap("SELECT status,error_code,current_attempt_id,workflow_resumed_at IS NOT NULL AS resumed,output_snapshot_json::jsonb->>'content' AS answer FROM agent_run WHERE tenant_code=? AND id=?",TENANT,runId);
                    report.put("outcome",run);report.put("tasks",tasks);report.put("history",history);report.put("externalCalls",List.copyOf(p.records.ledger));
                    var audits=db.queryForList("SELECT tool_code,status,idempotency_key,output_snapshot FROM agent_tool_execution_audit WHERE tenant_code=? AND idempotency_key LIKE ? ORDER BY id",TENANT,runId+":tool:%");
                    report.put("audit",audits.stream().map(a->Map.of("tool",a.get("tool_code"),"status",a.get("status"),"key",a.get("idempotency_key"))).toList());
                    report.put("steps",db.queryForList("SELECT attempt_id,sequence_no,step_type,status,error_code FROM agent_run_step WHERE tenant_code=? AND agent_run_id=? ORDER BY attempt_id,sequence_no",TENANT,runId));
                    report.put("checkpoints",db.queryForList("SELECT attempt_id,sequence_no,snapshot_json::jsonb->>'logicalStepId' AS logical_step FROM agent_run_checkpoint WHERE tenant_code=? AND agent_run_id=? ORDER BY attempt_id,sequence_no",TENANT,runId));
                    report.put("preRevocationReadBack",List.copyOf(p.revokedLocators));
                    JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(id+".json").toFile(),report);
                    assertThat(run.get("resumed")).isEqualTo(true);
                    assertThat(tasks).containsExactly(c.path("task").asText());assertThat(history).isEqualTo(tasks);
                    if(timeout || revoked){
                        assertThat(run.get("status")).as("%s %s",id,run).isEqualTo("FAILED");
                        assertThat(run.get("answer")).isNull();
                        assertThat(run.get("error_code")).isEqualTo(timeout?"MCP_TIMEOUT":"KNOWLEDGE_RESULT_ACCESS_REVOKED");
                        if(timeout){
                            assertThat(p.records.ledger).hasSize(2);
                            assertThat(db.queryForObject("SELECT count(*) FROM agent_run_attempt WHERE tenant_code=? AND agent_run_id=?",Integer.class,TENANT,runId)).isEqualTo(2);
                        }else{
                            var receipt=JSON.readTree((String)audits.stream().filter(a->"knowledge_search".equals(a.get("tool_code"))).findFirst().orElseThrow().get("output_snapshot"));
                            assertThat(p.revokedLocators).isNotEmpty().allSatisfy(loc->assertThatThrownBy(()->p.originals.read(TENANT,BUYER,receipt.path("traceId").asText(),loc)).hasMessageContaining("Citation unavailable"));
                        }
                        continue;
                    }
                    assertThat(run.get("status")).as("%s %s",id,run).isEqualTo("SUCCEEDED");
                    var answer=JSON.readTree((String)run.get("answer"));
                    assertThat(answer.path("action").asText()).isEqualTo(c.path("action").asText());
                    assertThat(answer.path("decision").asText()).isEqualTo(c.path("decision").asText());
                    assertThat(audits).extracting(a->a.get("tool_code")).contains("knowledge_search");
                    assertThat(p.records.ledger).hasSize(joint?1:0);
                    var search=audits.stream().filter(a->"knowledge_search".equals(a.get("tool_code"))).findFirst().orElseThrow();
                    var output=JSON.readTree((String)search.get("output_snapshot"));
                    var trace=output.path("traceId").asText();
                    boolean found=false;
                    var readbacks=new ArrayList<Map<String,Object>>();
                    for(var evidence:output.path("evidence")){
                        String locator=evidence.path("reference").path("locator").asText();
                        var preview=p.originals.read(TENANT,BUYER,trace,locator);
                        readbacks.add(Map.of("trace",trace,"locator",locator,"sha256",preview.originalSha256(),"anchor",preview.anchor()));
                        assertThat(preview.originalSha256()).isEqualTo(p.gold.path("policySha256Lf").asText());
                        if(preview.excerpt().contains("不能断言"))found=true;
                        assertThatThrownBy(()->p.originals.read(TENANT,"other",trace,locator)).hasMessageContaining("Citation unavailable");
                    }
                    assertThat(found).as("required order clause: "+id).isTrue();
                    report.put("readBack",readbacks);
                    JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(id+".json").toFile(),report);
                    if(live){
                        assertThat(answer.path("citations").size()).isPositive();
                        boolean cited=false;
                        for(var ref:answer.path("citations"))cited|=p.originals.read(TENANT,BUYER,trace,ref.asText()).excerpt().contains("不能断言");
                        assertThat(cited).isTrue();
                    }
                }
            } finally {engine.close();}
        });
    }

    private static final class Scripted implements ModelProviderPort {
        private final P17Pilot p; private final boolean joint; private int phase; private String citation;
        Scripted(P17Pilot p,boolean joint){this.p=p;this.joint=joint;}
        public AgentProviderType providerType(){return AgentProviderType.MOCK;}
        public ModelProviderResponse invoke(ModelProviderRequest request){
            Object content;
            if(request.systemPrompt().startsWith("Merge")) content="Bounded test notes";
            else if(phase++==0) content=Map.of("schemaVersion","1","mode",joint?"RAG_MCP":"RAG","reason","check","requestedTools",
                    joint?List.of("knowledge_search",p.toolCode):List.of("knowledge_search"),"steps",List.of("verify"));
            else if(phase==2)content=Map.of("action","TOOL_CALL","tool","knowledge_search","arguments",Map.of("query","没有该采购单的实时预算、审批、报价有效期、准入和签署权限记录，能否断言可以下单？","maxResults",3));
            else if(citation==null && !request.userInput().contains("引用原文片段（数据）：")){
                var ref=java.util.regex.Pattern.compile("[0-9]+:tool:[0-9]+:knowledge_search").matcher(request.userInput());
                assertThat(ref.find()).isTrue();
                content=Map.of("action","READ_RESULT","reference",ref.group(),"pointer","/evidence/0/reference/locator");
            }
            else {
                if(citation==null){
                    String marker="引用原文片段（数据）：";
                    try {citation=JSON.readTree(request.userInput().substring(request.userInput().lastIndexOf(marker)+marker.length())).required("value").textValue();}
                    catch(java.io.IOException ex){throw new AssertionError(ex);}
                }
                if(joint && (request.toolResult()==null || !p.toolCode.equals(request.toolResult().toolName())))content=Map.of("action","TOOL_CALL","tool",p.toolCode,"arguments",Map.of("orderId","P-17"));
                else {
                    com.fasterxml.jackson.databind.JsonNode receipt;
                    try {receipt=joint?JSON.readTree(request.toolResult().output()):JSON.nullNode();}
                    catch(java.io.IOException ex){throw new AssertionError(ex);}
                    boolean valid="P-17".equals(receipt.path("subject").asText());
                    boolean met=List.of("approval","budget","quoteValidity","admission","signingAuthority").stream()
                            .allMatch(f->"MET".equals(receipt.path("facts").path(f).asText()));
                    content=Map.of("action",valid?"ANSWER":"ABSTAIN","decision",!valid?"MISSING_LIVE_RECORDS":
                            met?"ORDER_READY":"ORDER_NOT_READY","citations",List.of(citation));
                }
            }
            return new ModelProviderResponse(content instanceof String s?s:JSON.valueToTree(content).toString(),"scripted","isolated","stop",1,1,0,1);
        }
    }
}
