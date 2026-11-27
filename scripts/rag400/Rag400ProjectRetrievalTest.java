package io.github.illuseahashmap.workflow.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.knowledge.ingestion.application.DocumentIngestionService;
import io.github.illuseahashmap.knowledge.ingestion.domain.TextChunker;
import io.github.illuseahashmap.knowledge.retrieval.application.KeywordRetriever;
import io.github.illuseahashmap.knowledge.retrieval.application.port.ChunkSearchPort;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.SourceIndexBinding;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
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
        var queries = Files.readAllLines(input.resolve("dev.queries.jsonl"), StandardCharsets.UTF_8);
        assertThat(queries).hasSize(200);
        withScratchDatabase(url -> {
            Flyway.configure().dataSource(url,databaseUser,databasePassword)
                .locations("classpath:db/migration/platform","classpath:db/migration/vector").load().migrate();
            var ds = new DriverManagerDataSource(url,databaseUser,databasePassword);
            var jdbc = new NamedParameterJdbcTemplate(ds);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            var store = new NativeEvidenceStore(jdbc,new PostgresKnowledgeManagementAdapter(jdbc),JSON);
            var indexes = new PostgresKnowledgeIndexAdapter(jdbc);
            var ingestion = new DocumentIngestionService(new PostgresKnowledgeDocumentStore(jdbc),indexes,new TextChunker(1200,120));
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
            var captured=new ArrayList<ChunkSearchPort.SearchHit>();
            var retriever=new KeywordRetriever((t,r,k) -> { var hits=adapter.search(t,r,k); captured.addAll(hits);return hits; });
            var raw=new ArrayList<String>();
            for (String line:queries) {
                JsonNode q=JSON.readTree(line);
                captured.clear();
                var request=new RetrievalRequest(q.get("query").asText(),scopes,List.of(),null,10,
                    RetrievalRequest.StrategyHint.KEYWORD,0,List.of(),0,
                    bindings.stream().filter(b->scopes.contains(b.sourceCode())).toList());
                long start=System.nanoTime();
                var result=retriever.retrieve(tenant,request);
                double ms=(System.nanoTime()-start)/1e6;
                var hits=new ArrayList<Map<String,Object>>();
                for (var hit:captured) {
                    var c=hit.chunk(); var code=c.documentVersion().externalDocumentId();
                    var loc=locators.get(code).get(c.ordinal());
                    hits.add(Map.of("rank",hits.size()+1,"documentCode",code,"databaseVersion",c.documentVersion().version(),
                        "scope",c.documentVersion().sourceCode(),"chunkId",c.chunkId(),"ordinal",c.ordinal(),
                        "startLine",Integer.parseInt(loc.start()),"endLine",Integer.parseInt(loc.end()),
                        "text",c.text(),"score",hit.score()));
                }
                raw.add(JSON.writeValueAsString(Map.of("caseId",q.get("caseId").asText(),"status","SUCCESS",
                    "snapshotSha256",sha(snapshotBytes),"latencyMs",ms,"retrievalStatus",result.status(),
                    "abstained",result.abstained(),"traceId",result.retrievalTraceId(),"hits",hits)));
            }
            Files.write(output.resolve("project-raw.jsonl"),raw,StandardCharsets.UTF_8);
            Files.writeString(output.resolve("project-run.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("queries",queries.size(),"documents",snapshot.get("documents").size(),"chunks",chunks,
                    "indexBindings",bindings,"strategy","KEYWORD","maxResults",10,
                    "tenantMapping","rag400-sandbox -> default in disposable PostgreSQL database",
                    "permissionBoundary","actual SQL scope filtering; not HTTP login/principal authorization acceptance",
                    "modelCalled",false,"snapshotSha256",sha(snapshotBytes))));
            assertThat(raw).hasSize(200);
        });
    }
}
