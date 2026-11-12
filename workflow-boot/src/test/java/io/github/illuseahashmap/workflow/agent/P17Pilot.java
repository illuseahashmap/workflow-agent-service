package io.github.illuseahashmap.workflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.application.dto.*;
import io.github.illuseahashmap.agent.definition.application.impl.AgentDefinitionServiceImpl;
import io.github.illuseahashmap.agent.definition.domain.*;
import io.github.illuseahashmap.agent.definition.infrastructure.persistence.*;
import io.github.illuseahashmap.agent.mcp.application.*;
import io.github.illuseahashmap.agent.mcp.application.dto.McpConnectorCommand;
import io.github.illuseahashmap.agent.mcp.application.impl.*;
import io.github.illuseahashmap.agent.mcp.application.port.*;
import io.github.illuseahashmap.agent.mcp.domain.McpConnectorVersion;
import io.github.illuseahashmap.agent.mcp.infrastructure.persistence.*;
import io.github.illuseahashmap.agent.provider.application.dto.AgentProviderCommand;
import io.github.illuseahashmap.agent.provider.application.impl.AgentProviderServiceImpl;
import io.github.illuseahashmap.agent.provider.domain.AgentProviderType;
import io.github.illuseahashmap.agent.provider.infrastructure.crypto.AesGcmAgentCredentialCipher;
import io.github.illuseahashmap.agent.provider.infrastructure.persistence.*;
import io.github.illuseahashmap.agent.runtime.application.*;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.agent.runtime.infrastructure.persistence.*;
import io.github.illuseahashmap.knowledge.ingestion.application.DocumentIngestionService;
import io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker;
import io.github.illuseahashmap.knowledge.retrieval.application.*;
import io.github.illuseahashmap.knowledge.retrieval.application.port.*;
import io.github.illuseahashmap.workflow.knowledge.*;
import io.github.illuseahashmap.workflow.shared.context.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Published objects are shared across trials; only the external order system is a controlled fixture. */
final class P17Pilot {
    static final Path DIR=Path.of("../docs/fixtures/knowledge/pilots/p17-joint-v1");
    static final String TENANT="pilot",BUYER="buyer",SCOPE="procurement",ARK="https://ark.cn-beijing.volces.com/api/v3/responses";
    static final ObjectMapper JSON=new ObjectMapper();
    final TenantProvider tenant=()->new TenantContext.TenantInfo(TENANT,TENANT,TENANT);
    final CurrentPrincipalProvider buyer=()->new CurrentPrincipal("USER",BUYER,BUYER,BUYER,TENANT,Set.of(),Set.of());
    final AgentOutputSchemaValidator validator=new AgentOutputSchemaValidator(JSON);
    final JdbcAgentDefinitionRepository definitions;
    final JdbcAgentDefinitionVersionRepository versions;
    final JdbcAgentProviderRepository providers;
    final JdbcAgentCredentialResolver credentials;
    final AgentToolRegistry registry;
    final NativeEvidenceStore originals;
    final PostgresKnowledgeManagementAdapter management;
    final JsonNode gold;
    final Records records;
    P17ScoreTrace observation;
    final List<String> revokedLocators=new ArrayList<>();
    final String model,toolCode;
    final long profile,index,provider,catalogId,snapshotId,policyVersion,jointVersion;

    P17Pilot(NamedParameterJdbcTemplate db, TransactionTemplate tx,boolean live) throws Exception {
        gold=JSON.readTree(DIR.resolve("cases.json").toFile());
        var sql=db.getJdbcTemplate();
        sql.update("INSERT INTO workflow_tenant(tenant_id,tenant_code,tenant_name) VALUES(?,?,?)",TENANT,TENANT,TENANT);
        management=new PostgresKnowledgeManagementAdapter(db);
        originals=new NativeEvidenceStore(db,management,JSON);
        byte[] md=Files.readString(DIR.resolve("../procurement-approval-v1/sources/approval.md")).replace("\r\n","\n").getBytes(StandardCharsets.UTF_8);
        assertThat(hash(md)).isEqualTo(gold.path("policySha256Lf").asText());
        var doc=tx.execute(s->originals.ingest(TENANT,SCOPE,"approval.md",1,"MD",md));
        var indexes=new PostgresKnowledgeIndexAdapter(db);
        var ingestion=new DocumentIngestionService(new PostgresKnowledgeDocumentStore(db),indexes,new TextChunker(1200,120));
        var ix=tx.execute(s->ingestion.ingest(TENANT,SCOPE,doc.documentVersionId()).indexVersion());
        index=ix.id();
        profile=tx.execute(s->{
            indexes.activate(ix);
            var draft=management.createProfile(TENANT,new KnowledgeManagementPort.CreateProfile("p17-policy","KEYWORD",5,0,index,List.of(SCOPE)));
            management.grant(TENANT,new KnowledgeManagementPort.GrantScope(BUYER,SCOPE));
            return management.publishProfile(TENANT,draft.id()).id();
        });
        var service=new KnowledgeRetrievalService(tenant,new KeywordRetriever(new PostgresKeywordChunkSearchAdapter(db)),
                new PostgresKnowledgeAccessPolicy(db),new PostgresRetrievalTraceRepository(db,JSON),new PostgresRetrievalProfileRepository(db),
                new PostgresAgentRetrievalProfileBinding(db),new PostgresEvidenceVisibilityAdapter(db));
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("retrieval",service);
        var search=new KnowledgeSearchAgentTool(beans.getBeanProvider(KnowledgeRetrievalUseCase.class),JSON);
        var mcp=new JdbcMcpCatalogRepository(db);
        records=new Records(JSON.readTree(DIR.resolve("receipts.json").toFile()));
        var governance=new JdbcAgentToolGovernanceRepository(db);
        registry=new AgentToolRegistry(List.of(search),governance,governance,validator,JSON,List.of(new McpAgentToolAdapter(mcp,records)),
                new JdbcAgentToolSnapshotRepository(db)){
            @Override public AgentTool.Result execute(String tenantCode,String versionSet,String nodeSet,String code,AgentTool.Request request){
                int before=records.ledger.size();
                AgentTool.Result result;
                try { result=super.execute(tenantCode,versionSet,nodeSet,code,request); }
                catch(McpClientException timeout){
                    if(timeout.failureKind()==McpFailureKind.TIMEOUT)observe(request,code,before);
                    throw timeout;
                }
                observe(request,code,before);
                if("REVOKED".equals(records.mode) && code.equals("knowledge_search")){
                    try {
                        var output=JSON.readTree(result.output());
                        for(var e:output.path("evidence")){
                            String loc=e.path("reference").path("locator").asText();
                            originals.read(TENANT,BUYER,output.path("traceId").asText(),loc);
                            revokedLocators.add(loc);
                        }
                    } catch(java.io.IOException e){throw new IllegalStateException(e);}
                    assertThat(revokedLocators).isNotEmpty();
                    assertThat(sql.update("DELETE FROM knowledge_principal_scope_grant WHERE tenant_code=? AND principal_id=? AND scope_code=?",TENANT,BUYER,SCOPE)).isOne();
                    observation.revoked();
                }
                return result;
            }
            private void observe(AgentTool.Request request,String code,int before){
                String key=request.runId()+":"+request.logicalStepId()+":"+code;
                boolean policy="knowledge_search".equals(code);
                assertThat(records.ledger.size()-before).isEqualTo(policy?0:1);
                try {observation.record(request,code,governance.findByIdempotencyKey(request.tenantCode(),code,key).orElseThrow(),
                        policy?null:records.ledger.get(before),originals,gold.path("policySha256Lf").asText());}
                catch(Exception failure){throw new AssertionError("Cannot join tool observations to "+key,failure);}
            }
        };
        var catalogs=new McpCatalogServiceImpl(mcp,records,new JdbcMcpToolRegistrationRepository(db),tenant,buyer,JSON,validator,new McpCatalogPersistenceService(mcp));
        var connector=tx.execute(s->catalogs.create(new McpConnectorCommand("p17-records","采购记录只读夹具","https://p17.invalid/mcp",null,null,10)));
        var discovered=tx.execute(s->catalogs.discover(connector.id()));
        catalogId=discovered.catalogVersionId();snapshotId=discovered.tools().getFirst().snapshotId();
        toolCode=discovered.tools().getFirst().registryToolCode();
        tx.executeWithoutResult(s->catalogs.publish(catalogId));
        sql.update("UPDATE agent_tool_definition SET enabled=true WHERE tool_code='knowledge_search'");
        sql.update("INSERT INTO agent_tool_tenant_grant(tenant_code,tool_code) VALUES(?,'knowledge_search') ON CONFLICT(tenant_code,tool_code) DO UPDATE SET enabled=true",TENANT);
        providers=new JdbcAgentProviderRepository(db);
        byte[] key=new byte[32];new SecureRandom().nextBytes(key);
        var cipher=new AesGcmAgentCredentialCipher(Base64.getEncoder().encodeToString(key));
        credentials=new JdbcAgentCredentialResolver(db,cipher);
        var ps=new AgentProviderServiceImpl(providers,cipher,tenant);
        model=live?Objects.requireNonNull(System.getenv("WORKFLOW_TEST_ARK_CHAT_MODEL")):"scripted-smoke";
        provider=tx.execute(s->ps.create(new AgentProviderCommand("p17-provider","P17",live?AgentProviderType.OPENAI_COMPATIBLE:AgentProviderType.MOCK,
                live?ARK:null,model,live?Objects.requireNonNull(System.getenv("ARK_API_KEY")):null,true)).id());
        definitions=new JdbcAgentDefinitionRepository(db); versions=new JdbcAgentDefinitionVersionRepository(db);
        var publish=new AgentDefinitionServiceImpl(definitions,versions,providers,tenant,buyer,JSON,validator,registry,new PostgresCitationPublicationAdapter(db));
        String prompt=Files.readString(DIR.resolve("e1-prompt.txt"));
        String schema=Files.readString(DIR.resolve("e1-output.schema.json"));
        policyVersion=publish(tx,publish,catalogs,prompt,schema,false);
        jointVersion=publish(tx,publish,catalogs,prompt,schema,true);
    }
    private long publish(TransactionTemplate tx,AgentDefinitionServiceImpl service,McpCatalogService catalogs,String prompt,String schema,boolean joint){
        return tx.execute(s->{
            String code=joint?"p17_joint":"p17_policy";
            var def=service.create(new AgentDefinitionCommand(code,code,"isolated evaluation",true));
            long id=versions.findLatest(TENANT,def.id()).orElseThrow().id();
            service.updateDraft(def.id(),id,new AgentVersionCommand(AgentExecutionMode.PLATFORM_AGENT,provider,model,prompt,120,
                    AgentFailurePolicy.FAIL_PROCESS,"{\"type\":\"object\"}",schema,List.of("knowledge_search"),profile));
            if(joint){
                catalogs.bind(id,snapshotId);
                catalogs.bind(id,snapshotId);
                assertThat(tools(id)).containsExactly("knowledge_search",toolCode);
                catalogs.unbind(id,snapshotId);
                assertThat(tools(id)).containsExactly("knowledge_search");
                catalogs.bind(id,snapshotId);
            }
            return service.publish(def.id(),id).id();
        });
    }
    private List<String> tools(long id){
        try {return JSON.readerForListOf(String.class).readValue(versions.findByVersionId(TENANT,id).orElseThrow().toolSetJson());}
        catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    static String hash(byte[] data) throws NoSuchAlgorithmException {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    static final class Records implements McpClientPort {
        final JsonNode data; String mode;
        final List<Map<String,Object>> ledger=new ArrayList<>();
        Records(JsonNode data){this.data=data;}
        public Session initialize(McpConnectorVersion c,Duration timeout){return new Session(c,"isolated",c.protocolVersion());}
        public List<Tool> listTools(Session s,Duration timeout){return List.of(new Tool("order_records_read","读取单笔采购单的五项实时条件",
                "{\"type\":\"object\",\"required\":[\"orderId\"],\"properties\":{\"orderId\":{\"type\":\"string\"}},\"additionalProperties\":false}"));}
        public CallResult callTool(Session session,String name,Map<String,Object> args,Duration timeout){
            if(!TENANT.equals(session.connector().tenantCode()) || !"order_records_read".equals(name) || mode==null ||
                    !Set.of("ALL_MET","LOW_BUDGET","EMPTY","FOREIGN_RECEIPT","TIMEOUT","REVOKED").contains(mode))
                throw new IllegalStateException("Outside read-only fixture");
            if(!(args.get("orderId") instanceof String subject))throw new IllegalArgumentException("orderId must be a string");
            var result=JSON.createObjectNode();
            if("TIMEOUT".equals(mode))result.put("status","TIMEOUT");
            else if("EMPTY".equals(mode) || !Set.of("P-17","P-18").contains(subject))result.put("status","EMPTY");
            else {
                var row=data.get("P-18".equals(subject)||"FOREIGN_RECEIPT".equals(mode)?"otherOrder":"LOW_BUDGET".equals(mode)?"lowBudget":"allMet");
                result.put("status","OK").setAll((com.fasterxml.jackson.databind.node.ObjectNode)row);
            }
            ledger.add(Map.of("name",name,"subject",subject,"receipt",result));
            if("TIMEOUT".equals(mode))throw new McpClientException("MCP_TIMEOUT",McpFailureKind.TIMEOUT,true,"Fixture timeout");
            return new CallResult(result.toString(),false);
        }
    }
}
