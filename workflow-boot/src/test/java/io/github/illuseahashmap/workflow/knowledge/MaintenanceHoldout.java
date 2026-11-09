package io.github.illuseahashmap.workflow.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.knowledge.catalog.domain.DocumentContent;
import io.github.illuseahashmap.knowledge.ingestion.application.DocumentIngestionService;
import io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker;
import io.github.illuseahashmap.knowledge.retrieval.application.port.*;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

final class MaintenanceHoldout {
    private static final Path FIX=Path.of("../docs/fixtures/knowledge/pilots/maintenance-holdout-v1");
    private static final ObjectMapper JSON=new ObjectMapper();
    record Q(String caseId,String query,String target,List<List<String>> requiredEvidenceGroups,List<String> wrongRows,
             List<String> factsMissing,String expectedDisposition,String expectedAnswer,List<String> forbiddenClaims){}
    record Sample(String format,String method,String caseId,int k,int repeat,long micros){}
    record Trial(String format,String method,String caseId,int k,List<String> ranked,List<List<String>> missing,
                 int wrongTable,int wrongRow,boolean firstWrongTable,boolean firstWrongRow){}
    static void run(NamedParameterJdbcTemplate jdbc,String tenant) throws Exception {
        for(String line:Files.readAllLines(FIX.resolve("inputs.sha256"))) {
            var pair=line.split(" ");assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(FIX.resolve(pair[0]))))).as(pair[0]).isEqualTo(pair[1]);
        }
        var questions=Files.readAllLines(FIX.resolve("cases.jsonl")).stream().map(s->{
            try{return JSON.readValue(s,Q.class);}catch(Exception e){throw new IllegalArgumentException(e);}
        }).toList();
        assertThat(questions).extracting(Q::caseId).hasSize(10).doesNotHaveDuplicates();
        var labels=Files.readAllLines(FIX.resolve("anchors.tsv")).stream().skip(1).map(s->s.split("\t")).toList();
        var scopes=new PostgresKnowledgeManagementAdapter(jdbc);
        var nativeStore=new NativeEvidenceStore(jdbc,scopes,JSON);
        var ix=new PostgresKnowledgeIndexAdapter(jdbc);
        var ingest=new DocumentIngestionService(new PostgresKnowledgeDocumentStore(jdbc),ix,
                new TextChunker(1200,120));
        List<ChunkSearchPort> engines=List.of(new FrozenLexicalControl(jdbc),new PostgresKeywordChunkSearchAdapter(jdbc));
        var quality=new LinkedHashMap<String,Trial>();var samples=new ArrayList<Sample>();
        for(String fmt:List.of("MD","XLSX","DOCX")) {
            String scope="holdout-"+fmt;
            var travel=DocumentContent.from(Files.readString(FIX.resolve("travel.txt")));
            scopes.createDocument(tenant,new KnowledgeManagementPort.CreateDocument(scope,scope,"travel",1,travel.sha256(),travel.text()));
            byte[] bytes=Files.readAllBytes(FIX.resolve("maintenance."+fmt.toLowerCase(Locale.ROOT)));
            var intake=nativeStore.ingest(tenant,scope,"maintenance",1,fmt,bytes);
            assertThat(intake.status()).as("%s parse: %s",fmt,intake.exclusions()).isEqualTo("PARSED");
            var coverage=nativeStore.coverage(tenant,scope,"maintenance",1);
            var keyOrder=new HashMap<Integer,String>();
            var index=ingest.ingest(tenant,scope,intake.documentVersionId()).indexVersion();ix.activate(index);
            var indexed=jdbc.getJdbcTemplate().queryForList("SELECT content FROM knowledge_chunk WHERE index_version_id=? AND document_version_id=? ORDER BY ordinal",
                    String.class,index.id(),intake.documentVersionId());
            for(var l:labels) {
                var locator=switch(fmt){
                    case "MD"->new NativeLocator(2,NativeLocator.Kind.MD_LINES,"",l[1],l[1],null);
                    case "DOCX"->new NativeLocator(2,NativeLocator.Kind.WORD_BLOCKS,"word/document.xml",l[2],l[2],null);
                    default->new NativeLocator(2,NativeLocator.Kind.XLSX_CELLS,"维护授权",l[3],l[4],null);
                };
                int ord=coverage.locations().indexOf(locator);
                assertThat(ord).as("%s %s",fmt,l[0]).isNotNegative();
                assertThat(indexed.get(ord)).contains(l[5]);
                assertThat(keyOrder.put(ord,l[0])).isNull();
            }
            assertThat(keyOrder).hasSize(18).hasSize(indexed.size());
            for(var q:questions) {
                assertThat(keyOrder.values()).containsAll(q.wrongRows());
                for(var group:q.requiredEvidenceGroups()) assertThat(keyOrder.values()).containsAll(group);
            }
            for(int pass=0;pass<5;pass++)for(int qi=0;qi<questions.size();qi++)for(int k:List.of(3,5,10)){
                var q=questions.get(qi);
                var request=new RetrievalRequest(q.query(),List.of(scope),List.of(),null,k,RetrievalRequest.StrategyHint.KEYWORD,0,List.of(),index.id());
                for(int j=0;j<2;j++) {
                    int engine=(j+pass+qi)%2;String method=engine==0?"control":"candidate";
                    long start=System.nanoTime();
                    var hits=engines.get(engine).search(tenant,request,k);
                    long micros=(System.nanoTime()-start)/1000;
                    if(engine==1 && k==5 && pass==4 && q.caseId().equals("H10")) {
                        var comparison=new io.github.illuseahashmap.knowledge.retrieval.application.KeywordRetriever(
                                (ignoredTenant,ignoredRequest,ignoredLimit)->hits).retrieve(tenant,request);
                        assertThat(comparison.status()).isEqualTo(io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalStatus.PARTIAL);
                        assertThat(comparison.abstained()).isTrue();
                    }
                    var ranked=hits.stream().map(h->{
                        assertThat(h.chunk().documentVersion().tenantCode()).isEqualTo(tenant);
                        return switch(h.chunk().documentVersion().externalDocumentId()){
                            case "maintenance"->Objects.requireNonNull(keyOrder.get(h.chunk().ordinal()));
                            case "travel"->"travel"; default->throw new AssertionError("Unexpected source");};
                    }).toList();
                    assertThat(ranked).hasSizeLessThanOrEqualTo(k).doesNotHaveDuplicates();
                    if(pass==0) continue;
                    var missing=q.requiredEvidenceGroups().stream().filter(g->Collections.disjoint(g,ranked)).toList();
                    var wrongTable=ranked.stream().filter(a->wrongTable(q,a)).toList();
                    var wrongRow=ranked.stream().filter(q.wrongRows()::contains).toList();
                    var trial=new Trial(fmt,method,q.caseId(),k,ranked,missing,wrongTable.size(),wrongRow.size(),
                            !ranked.isEmpty() && wrongTable.contains(ranked.getFirst()),!ranked.isEmpty() && wrongRow.contains(ranked.getFirst()));
                    var prior=quality.put(fmt+":"+method+":"+q.caseId()+":"+k,trial);
                    if(prior!=null)assertThat(trial).isEqualTo(prior);
                    if(engine==1) verifyScopedTrial(trial);
                    samples.add(new Sample(fmt,method,q.caseId(),k,pass,micros));
                }
            }
        }
        var out=Files.createDirectories(Path.of("target/maintenance-holdout"));
        assertThat(quality).hasSize(180);assertThat(samples).hasSize(720);
        write(out.resolve("quality.jsonl"),quality.values());write(out.resolve("latency.jsonl"),samples);
    }
    private static void verifyScopedTrial(Trial t) {
        var actual=assertThat(t.ranked()).as("%s %s K=%d",t.format(),t.caseId(),t.k());
        switch(t.caseId()) {
            case "H02" -> { actual.startsWith("r.mid").contains("basis","r.h"); }
            case "H05" -> actual.startsWith("c.high").contains("c.title","c.h");
            case "H08" -> {
                actual.startsWith("x.note").contains("x.a","x.b");
                if(t.k()>=5)actual.contains("x.h");
            }
            case "H09" -> actual.contains("x.b","x.h","x.note").doesNotContain("x.a");
            case "H10" -> actual.containsExactlyInAnyOrder("r.title","basis");
            default -> {return;}
        }
        assertThat(t.wrongTable()+t.wrongRow()).as("inapplicable evidence: %s",t).isZero();
    }
    private static boolean wrongTable(Q q,String anchor){
        if(anchor.equals("travel"))return true;
        if(!anchor.contains("."))return false;
        if(q.target().equals("outside"))return !anchor.endsWith(".h")&&!anchor.endsWith(".note")&&!anchor.endsWith(".title");
        return !anchor.startsWith(q.target()+".");
    }
    private static void write(Path path,Collection<?> rows)throws Exception{
        var lines=new ArrayList<String>();for(var row:rows)lines.add(JSON.writeValueAsString(row));Files.write(path,lines);
    }
}
