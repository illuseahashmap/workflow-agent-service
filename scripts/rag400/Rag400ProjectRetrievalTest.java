package io.github.illuseahashmap.workflow.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.knowledge.ingestion.application.DocumentIngestionService;
import io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker;
import io.github.illuseahashmap.knowledge.retrieval.application.KeywordRetriever;
import io.github.illuseahashmap.knowledge.retrieval.application.VectorRetriever;
import io.github.illuseahashmap.knowledge.retrieval.application.StrategyRetriever;
import io.github.illuseahashmap.knowledge.retrieval.application.Rag400ExpansionBridge;
import io.github.illuseahashmap.knowledge.retrieval.domain.ChunkEvidence;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalResult;
import io.github.illuseahashmap.knowledge.retrieval.application.port.ChunkSearchPort;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.SourceIndexBinding;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Isolated real PostgreSQL/native ingestion/keyword retrieval measurement; no gold input. */
@EnabledIfSystemProperty(named = "rag400.input", matches = ".+")
class Rag400ProjectRetrievalTest extends KnowledgeDatabaseFixture {
    static final ObjectMapper JSON = new ObjectMapper();
    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    @Test void developmentRetrieval() throws Exception {
        var input = Path.of(System.getProperty("rag400.input"));
        var output = Files.createDirectories(Path.of(System.getProperty("rag400.output")));
        byte[] snapshotBytes = Files.readAllBytes(input.resolve("knowledge-snapshot.json"));
        var snapshot = JSON.readTree(snapshotBytes);
        var auth = JSON.readTree(input.resolve("auth-fixture.json").toFile());
        var scopes = new ArrayList<String>();
        auth.get("grantedScopes").forEach(s -> scopes.add(s.asText()));
        var split = System.getProperty("rag400.split", "dev");
        assertThat(Set.of("dev", "calibration", "holdout-candidate")).contains(split);
        var queryDir = split.equals("holdout-candidate") ? input.resolve("holdout") : input;
        var queries = Files.readAllLines(queryDir.resolve(split + ".queries.jsonl"), StandardCharsets.UTF_8);
        int expected = Map.of("dev", 200, "calibration", 80, "holdout-candidate", 120).get(split);
        assertThat(queries).hasSize(expected);
        withScratchDatabase(url -> {
            Flyway.configure().dataSource(url,databaseUser,databasePassword)
                .locations(System.getProperty("rag400.migrationLocations",
                    "classpath:db/migration/platform,classpath:db/migration/vector").split(",")).load().migrate();
            var ds = new DriverManagerDataSource(url,databaseUser,databasePassword);
            var jdbc = new NamedParameterJdbcTemplate(ds);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            var store = new NativeEvidenceStore(jdbc,new PostgresKnowledgeManagementAdapter(jdbc),JSON);
            var indexes = new PostgresKnowledgeIndexAdapter(jdbc);
            var modes = List.of(System.getProperty("rag400.modes", "keyword").split(","));
            assertThat(Set.of("keyword","vector","hybrid","hybrid-expansion")).containsAll(modes);
            var embeddings = modes.stream().anyMatch(m -> !m.equals("keyword")) ? new Rag400EvaluationModels(jdbc.getJdbcTemplate()) : null;
            var ingestion = new DocumentIngestionService(new PostgresKnowledgeDocumentStore(jdbc),indexes,new TextChunker(1200,120),embeddings);
            var lastDoc = new TreeMap<String,Long>();
            var locators = new HashMap<String,List<NativeLocator>>();
            var bindings = new ArrayList<SourceIndexBinding>();
            String tenant = "default"; // Seeded test tenant, isolated DB; fixture tenant mapped explicitly.
            for (var doc : snapshot.get("documents")) {
                String code=doc.get("documentCode").asText(),scope=doc.get("scope").asText();
                byte[] bytes=Files.readAllBytes(input.resolve(doc.get("path").asText()));
                assertThat(sha(bytes)).isEqualTo(doc.get("sha256").asText());
                var intake=tx.execute(s -> store.ingest(tenant,scope,code,1,"MD",bytes));
                assertThat(intake.status()).isEqualTo("PARSED");
                lastDoc.put(scope,intake.documentVersionId());
                locators.put(code,store.coverage(tenant,scope,code,1).locations());
            }
            int chunks=0;
            for (var entry:lastDoc.entrySet()) {
                var result=tx.execute(s -> {
                    var r=ingestion.ingest(tenant,entry.getKey(),entry.getValue());
                    indexes.activate(r.indexVersion()); return r;
                });
                chunks+=result.chunks().size();
                bindings.add(new SourceIndexBinding(entry.getKey(),result.indexVersion().id()));
            }
            jdbc.getJdbcTemplate().execute("ANALYZE knowledge_chunk,knowledge_document_version,knowledge_source,knowledge_index_version,knowledge_scope_term");
            var adapter=new PostgresKeywordChunkSearchAdapter(jdbc);
            var vectorAdapter=new PostgresVectorSearchAdapter(jdbc);
            var captured=new HashMap<String,ChunkSearchPort.SearchHit>();
            var keyword=new KeywordRetriever((t,r,k) -> {
                var found=adapter.search(t,r,k); found.forEach(h -> captured.put(h.chunk().chunkId(),h)); return found;
            });
            var vector=embeddings==null ? null : new VectorRetriever(embeddings,(t,r,v,m,k) -> {
                var found=vectorAdapter.search(t,r,v,m,k); found.forEach(h -> captured.put(h.chunk().chunkId(),h)); return found;
            });
            var retriever=new StrategyRetriever(keyword,vector);
            var auxiliary=new HashMap<String,String>();
            if (modes.contains("hybrid-expansion")) {
                for (String row:Files.readAllLines(Path.of(System.getProperty("rag400.auxiliary")),StandardCharsets.UTF_8)) {
                    var entry=JSON.readTree(row);
                    if (entry.get("status").asText().equals("SUCCESS")) auxiliary.put(entry.get("caseId").asText(),entry.get("auxiliaryQuery").asText());
                }
            }
            if (embeddings!=null) {
                var inputs=new ArrayList<String>();
                for (String row:queries) inputs.add(JSON.readTree(row).get("query").asText());
                inputs.addAll(auxiliary.values());
                embeddings.embedQuery(inputs);
            }
            for (String mode:modes) {
                var raw=new ArrayList<String>();
                var modeOutput=Files.createDirectories(output.resolve(mode).resolve("retrieval"));
                for (String line:queries) {
                    JsonNode q=JSON.readTree(line);
                    captured.clear();
                    var hint=mode.equals("keyword") ? RetrievalRequest.StrategyHint.KEYWORD :
                        mode.equals("vector") ? RetrievalRequest.StrategyHint.VECTOR : RetrievalRequest.StrategyHint.HYBRID;
                    String aux=auxiliary.get(q.get("caseId").asText());
                    var request=new RetrievalRequest(q.get("query").asText(),scopes,List.of(),null,10,
                        hint,0,List.of(),0,bindings.stream().filter(b->scopes.contains(b.sourceCode())).toList(),
                        mode.equals("hybrid-expansion") && aux!=null ? List.of(aux) : List.of());
                    long start=System.nanoTime();
                    RetrievalResult result;
                    Object expansionTrace=Map.of("policy","DISABLED");
                    if (mode.equals("hybrid-expansion")) {
                        var expanded=Rag400ExpansionBridge.retrieve(retriever,tenant,request);
                        result=expanded.result(); expansionTrace=expanded.trace();
                    } else result=retriever.retrieve(tenant,request);
                    double ms=(System.nanoTime()-start)/1e6;
                    var hits=new ArrayList<Map<String,Object>>();
                    // Score ONLY the final production evidence list, never union all channel candidates.
                    for (var selected:result.evidence()) {
                        var evidence=(ChunkEvidence)selected;
                        var hit=captured.get(evidence.reference().locator());
                        assertThat(hit).isNotNull();
                        var c=hit.chunk(); var code=c.documentVersion().externalDocumentId();
                        var loc=locators.get(code).get(c.ordinal());
                        hits.add(Map.of("rank",hits.size()+1,"documentCode",code,"databaseVersion",c.documentVersion().version(),
                            "scope",c.documentVersion().sourceCode(),"chunkId",c.chunkId(),"ordinal",c.ordinal(),
                            "startLine",Integer.parseInt(loc.start()),"endLine",Integer.parseInt(loc.end()),
                            "text",evidence.text(),"score",evidence.score()));
                    }
                    assertThat(hits.size()).isLessThanOrEqualTo(10);
                    var row=new LinkedHashMap<String,Object>();
                    row.put("caseId",q.get("caseId").asText()); row.put("status","SUCCESS");
                    row.put("snapshotSha256",sha(snapshotBytes)); row.put("latencyMs",ms);
                    row.put("retrievalStatus",result.status()); row.put("abstained",result.abstained());
                    row.put("traceId",result.retrievalTraceId()); row.put("hits",hits);
                    row.put("mode",mode); row.put("expansionTrace",expansionTrace);
                    row.put("fixtureOnly",Boolean.getBoolean("rag400.fixture"));
                    raw.add(JSON.writeValueAsString(row));
                }
                Files.write(modeOutput.resolve("project-raw.jsonl"),raw,StandardCharsets.UTF_8);
                var metadata=new LinkedHashMap<String,Object>();
                metadata.put("queries",queries.size()); metadata.put("documents",snapshot.get("documents").size());
                metadata.put("chunks",chunks); metadata.put("indexBindings",bindings); metadata.put("strategy",mode);
                metadata.put("maxResults",10); metadata.put("snapshotSha256",sha(snapshotBytes));
                metadata.put("embedding",embeddings==null ? Map.of("enabled",false) : embeddings.metrics());
                metadata.put("permissionBoundary","actual fixed SQL scopes; not HTTP principal authorization");
                Files.writeString(modeOutput.resolve("project-run.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
                assertThat(raw).hasSize(expected);
            }
        });
    }
}
