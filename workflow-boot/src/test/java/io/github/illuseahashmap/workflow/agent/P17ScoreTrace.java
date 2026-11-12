package io.github.illuseahashmap.workflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.agent.runtime.application.port.AgentToolExecutionAuditRepository.Audit;
import io.github.illuseahashmap.workflow.knowledge.NativeEvidenceStore;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static io.github.illuseahashmap.workflow.agent.P17Pilot.*;
import static org.assertj.core.api.Assertions.assertThat;

/** A synchronous, per-trial observer. Never consult the case's expected decision or task. */
final class P17ScoreTrace {
    final long run; final String process;
    final JdbcTemplate db;
    private final ObjectNode binding, trace=JSON.createObjectNode();
    private final Path dir;
    private final Map<String,String> lines=new HashMap<>(), locators=new LinkedHashMap<>();
    private final List<ObjectNode> calls=new ArrayList<>();
    private final List<String> observedToolNames=new ArrayList<>();
    private ObjectNode retrieval;
    private int seq;
    private long completedMs;
    P17ScoreTrace(JdbcTemplate db,Path batch,String id,long run,String process) throws Exception {
        this.db=db;this.run=run;this.process=process;dir=Files.createDirectory(batch.resolve(id));
        binding=JSON.createObjectNode().put("trialId",batch.getFileName()+":"+id).put("caseId",id)
                .put("agentRunId",Long.toString(run)).put("processInstanceId",process).put("tenant",TENANT).put("principal",BUYER);
        for(String row:Files.readAllLines(DIR.resolve("../procurement-approval-v1/anchors.tsv")).stream().skip(1).toList()){
            var cells=row.split("\t");lines.put(cells[1],cells[0]);
        }
    }
    private void section(String name,Object data){
        var section=trace.putObject(name);section.set("binding",binding);section.put("complete",true);section.set("data",JSON.valueToTree(data));
    }
    void record(AgentTool.Request req,String code,Audit audit,Map<String,Object> external,NativeEvidenceStore originals,String sha) throws Exception {
        assertThat(req.runId()).isEqualTo(run);assertThat(req.processInstanceId()).isEqualTo(process);
        assertThat(req.principalId()).isEqualTo(BUYER);assertThat(req.tenantCode()).isEqualTo(TENANT);
        String key=run+":"+req.logicalStepId()+":"+code;
        assertThat(audit.idempotencyKey()).isEqualTo(key);assertThat(audit.traceId()).isEqualTo(req.traceId());
        int attempt=attempt();
        boolean policy=code.equals("knowledge_search");
        var call=JSON.createObjectNode().put("id",attempt+":"+key).put("sourceTrial",binding.path("trialId").textValue()).put("attempt",attempt).put("policy","ALLOW")
                .put("operation","EXECUTE").putNull("origin").put("reauthorized",false).put("late",false).put("used",true);
        if(policy){
            assertThat(retrieval).as("one policy retrieval per pilot trial").isNull();
            assertThat(audit.status()).isEqualTo("SUCCEEDED");
            var output=JSON.readTree(audit.output());String tid=output.required("traceId").textValue();
            retrieval=JSON.createObjectNode().put("at",++seq).put("traceId",tid).putNull("revokedAt");
            for(var evidence:output.required("evidence")){
                var loc=evidence.required("reference").required("locator").textValue();
                var source=originals.read(TENANT,BUYER,tid,loc);
                assertThat(source.originalSha256()).isEqualTo(sha);
                var anchor=source.anchor();
                String label=anchor.kind().name().equals("MD_LINES") && anchor.start().equals(anchor.end())?lines.get(anchor.start()):null;
                locators.put(loc,label==null?"unmapped:"+loc:label);
            }
            retrieval.put("snapshot","procurement-approval-md-v1");
            retrieval.set("hits",JSON.valueToTree(locators.values()));retrieval.set("readBack",JSON.valueToTree(locators.values()));
            call.put("name",code).putNull("subject").putObject("receipt").put("status","OK").put("traceId",tid);
        }else{
            var receipt=JSON.valueToTree(external.get("receipt"));
            String name=external.get("name").toString();observedToolNames.add(name);
            assertThat(req.arguments().get("orderId")).isEqualTo(external.get("subject"));
            if("TIMEOUT".equals(receipt.path("status").asText()))assertThat(audit.status()).isEqualTo("FAILED");
            else {assertThat(audit.status()).isEqualTo("SUCCEEDED");assertThat(JSON.readTree(audit.output())).isEqualTo(receipt);}
            call.put("name",name).put("subject",external.get("subject").toString()).set("receipt",receipt);
        }
        calls.add(call.put("at",++seq));
    }
    void revoked(){retrieval.put("revokedAt",++seq);calls.getLast().put("used",false);}
    private int attempt(){return db.queryForObject("""
            SELECT a.attempt_no FROM agent_run r JOIN agent_run_attempt a ON a.id=r.current_attempt_id
              AND a.tenant_code=r.tenant_code AND a.agent_run_id=r.id
            WHERE r.tenant_code=? AND r.id=? AND r.process_instance_id=? AND r.requested_by=?
            """,Integer.class,TENANT,run,process,BUYER);}
    void completed() throws Exception {
        binding.put("attempt",attempt());trace.put("schemaVersion",1).set("binding",binding);
        var row=db.queryForMap("""
                SELECT status,error_code,output_snapshot_json::jsonb->>'content' AS content,
                       floor(extract(epoch FROM completed_at)*1000)::bigint AS ms
                  FROM agent_run WHERE tenant_code=? AND id=? AND process_instance_id=? AND requested_by=?
                """,TENANT,run,process,BUYER);
        completedMs=((Number)row.get("ms")).longValue();
        var answer=JSON.createObjectNode().put("at",++seq).put("completedMs",completedMs);
        var refs=answer.putArray("citations");
        if(row.get("content")!=null){
            var content=JSON.readTree((String)row.get("content"));
            assertThat(row.get("status")).isEqualTo("SUCCEEDED");
            answer.put("action",content.required("action").asText()).put("decision",content.required("decision").asText());
            for(var ref:content.required("citations"))refs.add(locators.getOrDefault(ref.asText(),"unsupported:"+ref.asText()));
        }else{
            assertThat(row.get("status")).isEqualTo("FAILED");
            String error=(String)row.get("error_code");
            answer.put("platformError",error).put("action","HUMAN_REVIEW").put("decision",switch(error){
                case "MCP_TIMEOUT" -> "LIVE_TIMEOUT";case "KNOWLEDGE_RESULT_ACCESS_REVOKED" -> "AUTH_REVOKED";default -> "UNKNOWN";});
        }
        // Pilot output is schema-closed (action, decision, citations); a revoked failure has no output at all.
        answer.put("restrictedDisclosures",retrieval!=null && !retrieval.path("revokedAt").isNull() && row.get("content")!=null?1:0);
        section("answer",answer);
        if(retrieval!=null)section("retrieval",retrieval);
        section("audit",Map.of("calls",calls));
        write("manifest.json",binding);
    }
    void finish(List<String> tasks,List<String> history,List<Map<String,Object>> external,long firstTaskMs,long observedMs) throws Exception {
        assertThat(firstTaskMs).as("Flowable history must postdate the committed run").isGreaterThanOrEqualTo(completedMs);
        section("flow",Map.of("active",tasks,"history",history,"observedMs",observedMs,"enteredAt",++seq));
        assertThat(external.stream().map(e->e.get("name").toString()).toList()).isEqualTo(observedToolNames);
        section("ledger",Map.of("submissions",count(external,"SUBMIT_ORDER"),"debits",count(external,"DEBIT_BUDGET")));
        write("trace.json",trace);
    }
    private long count(List<Map<String,Object>> external,String name){return external.stream().filter(e->name.equals(e.get("name"))).count();}
    private void write(String name,JsonNode data) throws Exception {JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(name).toFile(),data);}
}
