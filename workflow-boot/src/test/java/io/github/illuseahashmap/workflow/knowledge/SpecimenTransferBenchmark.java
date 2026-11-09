package io.github.illuseahashmap.workflow.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.knowledge.ingestion.application.DocumentIngestionService;
import io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in measurement. Score even negative outcomes; integrity errors must fail the experiment. */
final class SpecimenTransferBenchmark {
    static final Path INPUT=Path.of("../docs/fixtures/knowledge/pilots/specimen-transfer-v1");
    static final ObjectMapper JSON=new ObjectMapper();
    record Case(String id,String query,List<List<Integer>> requiredEvidenceGroups,List<Integer> forbiddenEvidence,
                String expectedDisposition,String expectedAnswer) {}
    record Snapshot(long index,List<NativeLocator> locations,int chunks,int docs){}
    record Observation(String caseId,int worker,int pass,long us,List<String> ranked){}
    static void run(DataSource ds) throws Exception {
        var bytes=verified("policy.md","ef68031d33b69ba7e7321f424555e5223f3665a2c006b8971fb11b76956f3050");
        var cases=new ArrayList<Case>();
        for(String line:new String(verified("cases.jsonl","84222c1a5b19ffd730680c27d72eea912a10c839d91513086b77dabb04f46f00"),StandardCharsets.UTF_8).lines().toList())
            cases.add(JSON.readValue(line,Case.class));
        assertThat(cases).hasSize(8).extracting(Case::id).doesNotHaveDuplicates();
        var jdbc=new NamedParameterJdbcTemplate(ds);
        var summary=new ArrayList<>(List.of("noiseDocs\tdocs\tchunks\tworkers\tsamples\tcomplete\tcontaminated\tcleanComplete\tp95ms"));
        var raw=new ArrayList<>(List.of("noiseDocs\tworkers\tworker\tpass\tcaseId\tmicros"));
        var quality=new ArrayList<String>();
        var storage=new ArrayList<>(List.of("noiseDocs\tindexChunks\tdirectoryHeaders\ttermPostings\tdistinctTerms\twholeTableBytesIncludingIndexes"));
        var out=Files.createDirectories(Path.of("target/specimen-transfer"));
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        for(int noise:List.of(0,32)) {
            String scope="transport"+noise;
            var snap=Objects.requireNonNull(tx.execute(s->seed(jdbc,scope,bytes,noise)));
            var lines=snap.locations.stream().map(l->Integer.parseInt(l.start())).toList();
            assertThat(lines).doesNotHaveDuplicates();
            for(var q:cases) {
                for(var group:q.requiredEvidenceGroups)assertThat(lines).containsAll(group);
                assertThat(lines).containsAll(q.forbiddenEvidence);
            }
            // Fresh database: collect stats on both sides of every eligibility join. Analyzing
            // only chunks biases join order and makes the result dependent on autovacuum timing.
            jdbc.getJdbcTemplate().execute("ANALYZE knowledge_chunk, knowledge_document_version, knowledge_source, knowledge_index_version, knowledge_scope_term");
            storage.add(jdbc.getJdbcTemplate().queryForObject("""
                    SELECT concat_ws(chr(9),?,?,count(distinct chunk_id),count(*),count(distinct term),
                                     pg_total_relation_size('knowledge_scope_term'))
                    FROM knowledge_scope_term WHERE tenant_code='default' AND index_version_id=?
                    """,String.class,noise,snap.chunks,snap.index));
            var seen=new HashMap<String,List<String>>();
            for(int workers:List.of(1,4)){
                var all=measure(ds,scope,snap,cases,workers);
                assertThat(all).hasSize(64);
                int complete=0,bad=0,clean=0;
                for(var q:cases){
                    var obs=all.stream().filter(o->o.caseId.equals(q.id)).toList();
                    assertThat(obs).hasSize(8);
                    var ranked=obs.getFirst().ranked;
                    assertThat(ranked).hasSizeLessThanOrEqualTo(5).doesNotHaveDuplicates();
                    obs.forEach(o->assertThat(o.ranked).isEqualTo(ranked));
                    var prior=seen.put(q.id,ranked);
                    if(prior!=null)assertThat(ranked).isEqualTo(prior);
                    var missing=q.requiredEvidenceGroups.stream().filter(g->g.stream().noneMatch(i->ranked.contains("L"+i))).toList();
                    var wrong=ranked.stream().filter(k->!k.startsWith("L") || q.forbiddenEvidence.contains(Integer.parseInt(k.substring(1)))).toList();
                    if(q.id.equals("S05")){
                        assertThat(missing).as("both applicability clauses at fixed Top5").isEmpty();
                        assertThat(wrong).isEmpty();
                    }
                    if(missing.isEmpty())complete++;
                    if(!wrong.isEmpty())bad++;
                    if(missing.isEmpty()&&wrong.isEmpty())clean++;
                    if(workers==1)quality.add(JSON.writeValueAsString(Map.of("noiseDocs",noise,"caseId",q.id,"ranked",ranked,
                            "missing",missing,"forbiddenHits",wrong,"actualDisposition","NOT_RUN")));
                }
                for(var o:all)raw.add(String.join("\t",""+noise,""+workers,""+o.worker,""+o.pass,o.caseId,""+o.us));
                long[] times=all.stream().mapToLong(Observation::us).sorted().toArray();
                summary.add(String.join("\t",""+noise,""+snap.docs,""+snap.chunks,""+workers,""+all.size(),""+complete,""+bad,""+clean,
                        ""+(times[(int)Math.ceil(times.length*0.95)-1]/1000.0)));
            }
            if(noise>0)for(var q:cases)if(Set.of("S05","S06","S07").contains(q.id)){
                // A fresh-session diagnostic, outside all measured requests. Use the adapter's
                // exact SQL and bindings; do not maintain a separate approximate query.
                var plan=new ArrayList<String>();
                var inspector=new NamedParameterJdbcTemplate(ds){
                    @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.namedparam.SqlParameterSource args,
                                                       org.springframework.jdbc.core.RowMapper<T> unused) {
                        plan.add(queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "+sql,args,String.class));
                        return List.of();
                    }
                };
                new PostgresKeywordChunkSearchAdapter(inspector).search("default",new RetrievalRequest(q.query,
                        List.of(scope),List.of(),null,5,RetrievalRequest.StrategyHint.KEYWORD,0,List.of(),snap.index),5);
                Files.writeString(out.resolve(q.id+"-plan.json"),plan.getFirst());
            }
        }
        Files.write(out.resolve("summary.tsv"),summary);Files.write(out.resolve("quality.jsonl"),quality);
        Files.write(out.resolve("latency.tsv"),raw);
        Files.write(out.resolve("storage.tsv"),storage);
    }
    private static byte[] verified(String name,String hash)throws Exception{
        byte[] bytes=Files.readString(INPUT.resolve(name)).replace("\r\n","\n").getBytes(StandardCharsets.UTF_8);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).as(name).isEqualTo(hash);
        return bytes;
    }
    private static Snapshot seed(NamedParameterJdbcTemplate jdbc,String scope,byte[] bytes,int count){
        var store=new NativeEvidenceStore(jdbc,new PostgresKnowledgeManagementAdapter(jdbc),JSON);
        var ix=new PostgresKnowledgeIndexAdapter(jdbc);
        var doc=store.ingest("default",scope,"specimen",1,"MD",bytes);
        assertThat(doc.status()).isEqualTo("PARSED");
        for(int i=0;i<count;i++){
            var md=new StringBuilder("# 第 "+i+" 区记录\n");
            for(int j=0;j<12;j++)md.append("\n## 仪器耗材回收站 "+i+" / "+j+"\n\n")
                    .append("| 容器 | 核签金额 Z（人民币元） | 处理 |\n| --- | --- | --- |\n")
                    .append("| 标准箱 | 0 < Z ≤ 3,600 | 登记交接。 |\n| 特制箱 | Z > 3,600 | 值班员核签。 |\n");
            assertThat(store.ingest("default",scope,"noise-"+i,1,"MD",md.toString().getBytes(StandardCharsets.UTF_8)).status()).isEqualTo("PARSED");
        }
        var ingestion=new DocumentIngestionService(new PostgresKnowledgeDocumentStore(jdbc),ix,new TextChunker(1200,120));
        var result=ingestion.ingest("default",scope,doc.documentVersionId());ix.activate(result.indexVersion());
        int docs=(int) result.chunks().stream().map(c->c.documentVersion().id()).distinct().count();
        assertThat(docs).isEqualTo(count+1);
        return new Snapshot(result.indexVersion().id(),store.coverage("default",scope,"specimen",1).locations(),result.chunks().size(),docs);
    }

    private static List<Observation> measure(DataSource ds,String scope,Snapshot snap,List<Case> cases,int workers)throws Exception{
        var start=new CyclicBarrier(workers);
        try(var pool=Executors.newFixedThreadPool(workers)){
            var pending=new ArrayList<Future<List<Observation>>>();
            for(int w=0;w<workers;w++){
                int worker=w;
                pending.add(pool.submit(()->{
                    try(var connection=ds.getConnection()){
                        connection.setReadOnly(true);
                        var adapter=new PostgresKeywordChunkSearchAdapter(new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection,true)));
                        var rows=new ArrayList<Observation>();
                        for(int pass=-1;pass<8/workers;pass++){
                            if(pass==0)start.await(60,TimeUnit.SECONDS);
                            for(int j=0;j<cases.size();j++){
                                var q=cases.get((j+worker+pass+1)%cases.size());
                                var req=new RetrievalRequest(q.query,List.of(scope),List.of(),null,5,RetrievalRequest.StrategyHint.KEYWORD,0,List.of(),snap.index);
                                long t=System.nanoTime();
                                var hits=adapter.search("default",req,5);
                                long us=(System.nanoTime()-t)/1000;
                                if(pass==0 && q.id.equals("S05")){
                                    var decision=new io.github.illuseahashmap.knowledge.retrieval.application.KeywordRetriever(
                                            (tenant,request,limit)->hits).retrieve("default",req);
                                    assertThat(decision.abstained()).isFalse();
                                    assertThat(decision.requiredEvidenceGroups()).hasSize(2).allMatch(group->!group.isEmpty());
                                }
                                var ranked=hits.stream().map(hit->{
                                    var c=hit.chunk();
                                    assertThat(c.documentVersion().sourceCode()).isEqualTo(scope);
                                    var external=c.documentVersion().externalDocumentId();
                                    return external.equals("specimen")?"L"+snap.locations.get(c.ordinal()).start():external+":"+c.ordinal();
                                }).toList();
                                if(pass>=0)rows.add(new Observation(q.id,worker,pass,us,ranked));
                            }
                        }
                        return rows;
                    }
                }));
            }
            var all=new ArrayList<Observation>();
            for(var future:pending)all.addAll(future.get(180,TimeUnit.SECONDS));
            return all;
        }
    }
}
