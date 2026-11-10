package io.github.illuseahashmap.workflow.knowledge;

import io.github.illuseahashmap.agent.definition.domain.*;
import io.github.illuseahashmap.agent.provider.application.ModelProviderRegistry;
import io.github.illuseahashmap.agent.provider.domain.*;
import io.github.illuseahashmap.agent.provider.infrastructure.openai.*;
import io.github.illuseahashmap.agent.runtime.application.*;
import io.github.illuseahashmap.agent.runtime.application.impl.PlatformAgentExecutor;
import io.github.illuseahashmap.agent.runtime.application.port.AgentExecutor;
import io.github.illuseahashmap.knowledge.retrieval.application.*;
import io.github.illuseahashmap.knowledge.retrieval.application.port.*;
import io.github.illuseahashmap.knowledge.retrieval.domain.*;
import io.github.illuseahashmap.workflow.agent.KnowledgeSearchAgentTool;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Frozen fixture and isolated tenant grants; opt-in mode also calls a real Ark chat model. */
final class SpecimenConditionAcceptance {
    static void run(DataSource ds,boolean live) throws Exception {
        var json=SpecimenTransferBenchmark.JSON;
        var dir=SpecimenTransferBenchmark.INPUT;
        String original=Files.readString(dir.resolve("policy.md")).replace("\r\n","\n");
        var query=Files.readAllLines(dir.resolve("cases.jsonl")).stream().map(line->{
            try {return json.readTree(line);} catch(Exception e){throw new IllegalStateException(e);}
        }).filter(c->"S06".equals(c.path("id").asText())).findFirst().orElseThrow().get("query").asText();
        var jdbc=new NamedParameterJdbcTemplate(ds);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var manage=new PostgresKnowledgeManagementAdapter(jdbc);
        var keyword=new KeywordRetriever(new PostgresKeywordChunkSearchAdapter(jdbc));
        for(String scope:live?List.of("with-trigger","without-trigger"):List.of("with-trigger","without-trigger","unresolved-trigger")){
            boolean complete=scope.equals("with-trigger");
            String text=complete?original:original.replaceFirst("(?m)^专项线路仅在[^\n]+",
                    scope.equals("unresolved-trigger")?"专项线路提交质量例会的条件尚待补录。其他线路仅在发生降雨时通知物流部门。":"");
            if(!complete)assertThat(text).isNotEqualTo(original).contains("必要时提交质量例会");
            var snap=tx.execute(s->SpecimenTransferBenchmark.seed(jdbc,scope,text.getBytes(StandardCharsets.UTF_8),0));
            var profile=tx.execute(s->manage.createProfile("default",new KnowledgeManagementPort.CreateProfile(
                    scope,"KEYWORD",5,0,snap.index(),List.of(scope))));
            tx.executeWithoutResult(s->{manage.publishProfile("default",profile.id());
                manage.grant("default",new KnowledgeManagementPort.GrantScope("reviewer",scope));});
            var req=new RetrievalRequest(query,List.of(scope),List.of(),null,5,RetrievalRequest.StrategyHint.KEYWORD,0,List.of(),snap.index());
            var found=keyword.retrieve("default",req);
            assertThat(found.requiredEvidenceGroups()).hasSize(3);
            assertThat(found.abstained()).isEqualTo(!complete);
            assertThat(found.status()).isEqualTo(complete?RetrievalStatus.SUCCESS:RetrievalStatus.PARTIAL);
            assertThat(found.requiredEvidenceGroups().get(1)).isNotEmpty();
            assertThat(found.requiredEvidenceGroups().get(2).isEmpty()).isEqualTo(!complete);
            var shortReq=new RetrievalRequest(query,List.of(scope),List.of(),null,2,RetrievalRequest.StrategyHint.KEYWORD,0,List.of(),snap.index());
            assertThat(keyword.retrieve("default",shortReq).abstained()).isTrue();

            // AgentVersion/profile binding is fixed fixture wiring; all reads, grants, traces,
            // evidence reauthorization, executor routing, tool execution and model I/O are real.
            var useCase=new KnowledgeRetrievalService(()->null,keyword,new PostgresKnowledgeAccessPolicy(jdbc),
                    new PostgresRetrievalTraceRepository(jdbc,json),new PostgresRetrievalProfileRepository(jdbc),
                    (tenant,version)->"default".equals(tenant)&&version==1?Optional.of(profile.id()):Optional.empty(),
                    new PostgresEvidenceVisibilityAdapter(jdbc));
            var beans=new DefaultListableBeanFactory();beans.registerSingleton("retrieval",useCase);
            var tool=new KnowledgeSearchAgentTool(beans.getBeanProvider(KnowledgeRetrievalUseCase.class),json);
            var registry=new AgentToolRegistry(List.of(tool));
            String toolSet="[\"knowledge_search\"]";
            var toolRequest=new io.github.illuseahashmap.agent.runtime.application.port.AgentTool.Request("default",
                    Map.of("query",query),Duration.ofSeconds(10),"trace-"+scope,"request-"+scope,null,0,"tool:1",1L,"reviewer");
            if(complete) {
                var body=json.readTree(registry.execute("default",toolSet,null,tool.name(),toolRequest).output());
                assertThat(body.path("status").asText()).isEqualTo("SUCCESS");
                assertThat(body.path("citations").size()).isEqualTo(5);
            } else assertThatThrownBy(()->registry.execute("default",toolSet,null,tool.name(),toolRequest))
                    .isInstanceOfSatisfying(AgentExecutionException.class,
                            e->assertThat(e.failure().errorCode()).isEqualTo("KNOWLEDGE_EVIDENCE_INSUFFICIENT"));
            if(!live)continue;
            String model=Objects.requireNonNull(System.getenv("WORKFLOW_TEST_ARK_CHAT_MODEL"),"Set the enabled chat model ID");
            var now=OffsetDateTime.now();
            var provider=new AgentProvider(1L,"default","ark","ark",AgentProviderType.OPENAI_COMPATIBLE,
                    "https://ark.cn-beijing.volces.com/api/v3/responses",model,true,true,null,now,now);
            String prompt="你是只读制度核查 Agent。必须选择 knowledge_search，把用户原问题逐字作为 query。不可使用常识补充。"
                    +"有依据才给出 answer，区分必要和有条件，并在 sources 列出支撑条件、金额档和表头的原文 reference.locator 值。";
            String schema="""
                    {"type":"object","required":["answer","sources"],"properties":{"answer":{"type":"string"},"sources":{"type":"array","items":{"type":"string"}}}}
                    """;
            var version=new AgentDefinitionVersion(1L,"default",1L,1,AgentVersionStatus.PUBLISHED,AgentExecutionMode.PLATFORM_AGENT,
                    1L,model,prompt,120,AgentFailurePolicy.FAIL_PROCESS,null,schema,"reviewer","reviewer",now,now,now,"[\"knowledge_search\"]");
            var adapter=new LangChain4jOpenAiModelProviderAdapter(json,new ProviderEndpointValidator(false,false),1048576);
            var executor=new PlatformAgentExecutor((t,id)->Objects.requireNonNull(System.getenv("ARK_API_KEY")),
                    new ModelProviderRegistry(List.of(adapter)),new AgentOutputSchemaValidator(json),registry,json,4);
            var cmd=new AgentExecutor.Command("default",version,provider,query,Duration.ofSeconds(120),UUID.randomUUID().toString(),
                    0,null,null,null,null,"reviewer");
            if(!complete){
                assertThatThrownBy(()->executor.execute(cmd)).isInstanceOfSatisfying(AgentExecutionException.class,
                        e->assertThat(e.failure().errorCode()).isEqualTo("KNOWLEDGE_EVIDENCE_INSUFFICIENT"));
            }else{
                var answer=executor.execute(cmd);
                assertThat(answer.steps()).extracting(AgentExecutor.StepResult::stepType).contains("TOOL_CALL","VALIDATION");
                var result=json.readTree(answer.modelResponse().content());
                assertThat(result.path("answer").asText()).contains("主管","断点","例会");
                var sources=new HashSet<String>();result.path("sources").forEach(v->sources.add(v.asText()));
                var candidates=found.citations().stream().map(c->c.reference().locator()).toList();
                assertThat(candidates).containsAll(sources);
                for(var group:found.requiredEvidenceGroups())
                    assertThat(group).anyMatch(ref->sources.contains(ref.locator()));
                System.out.println("S06 live verified answer: "+result.path("answer").asText());
            }
        }
    }
    private SpecimenConditionAcceptance(){}
}
