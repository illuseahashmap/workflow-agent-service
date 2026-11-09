package io.github.illuseahashmap.workflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.knowledge.retrieval.application.KnowledgeRetrievalService;
import io.github.illuseahashmap.knowledge.retrieval.application.RetrievalEvaluation;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** DEV retrieval-only experiment. The request never receives judgments, group IDs or answers. */
final class ProcurementRetrievalBaseline {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<String,String> HASHES=Map.of(
            "MD","edc5e208a2923d3263187325fb13abe47406d01d4a09fad7d310d7990c44ca6e",
            "DOCX","b184db3829ebfe852cf67343914283858594f04c386a0c29078a66e87ff46450",
            "XLSX","65959d85060e41b79ff662a0d0ae39bc661104ddc2fa713cc3ef08d0cef85b73");
    private static final Map<String,List<String>> MARKERS=Map.of(
            "basis",List.of("含税总对价","安装费用计入","必要费用未知"),
            "header",List.of("档位","元","审批与复核"), "low",List.of("2,000","直属负责人审批"),
            "mid",List.of("2,000","20,000","共同审批"), "high",List.of("20,000","复核预算","必要时"),
            "committee",List.of("未完成准入","未知时须核验"),"order",List.of("实时预算","不能断言"));
    record Expectation(String caseId,List<List<String>> requiredEvidenceGroups,Map<String,Integer> relevanceJudgments,
            Set<String> forbiddenEvidence,List<String> citationExpectations,List<String> factsGiven,List<String> factsMissing,
            Map<String,String> expectedDisposition,List<String> forbiddenClaims,Map<String,Object> agentPathExpectation) {}
    private final Path pilot;
    private final Map<String,String> questions=new LinkedHashMap<>();
    private final Map<String,Expectation> expectations=new LinkedHashMap<>();
    private final List<String[]> anchorRows;
    private final List<String> results=new ArrayList<>(), traceLog=new ArrayList<>();
    ProcurementRetrievalBaseline(Path pilot) throws Exception {
        this.pilot=pilot;
        for(String line:Files.readAllLines(pilot.resolve("retrieval-expectations.dev.jsonl"))) {
            var e=JSON.readValue(line,Expectation.class);
            assertThat(expectations.put(e.caseId(),e)).isNull();
            assertThat(e.expectedDisposition().get("action")).isIn("ANSWER","ABSTAIN","HUMAN_REVIEW");
            assertThat(e.citationExpectations()).isNotEmpty().doesNotHaveDuplicates().allMatch(id->e.relevanceJudgments().containsKey(id));
            assertThat(e.forbiddenClaims()).isNotEmpty();
        }
        for(String line:Files.readAllLines(pilot.resolve("cases.tsv")).stream().skip(1).toList()) {
            var q=line.split("\t");
            assertThat(questions.put(q[0],q[1])).isNull();
            // The original flat pilot labels remain a human-readable projection for these nine cases.
            assertThat(expectations.get(q[0]).requiredEvidenceGroups().stream().flatMap(List::stream).toList())
                    .containsExactly(q[2].split(","));
        }
        assertThat(questions.keySet()).hasSize(9).containsExactlyInAnyOrderElementsOf(expectations.keySet());
        anchorRows=Files.readAllLines(pilot.resolve("anchors.tsv")).stream().skip(1).map(s->s.split("\t")).toList();
        results.add("format\tcaseId\tK\treturned\tmatchedGroups\trequiredGroups\tcomplete\tmissingGroups\trankedAnchors\tunjudged\tpreciseRequiredCitations\texpectedCitations\texpectedDisposition\tactualDisposition");
    }

    void run(String fmt,String tenant,long version,long profile,long index,NativeEvidenceStore.Coverage coverage,
            KnowledgeRetrievalService service,NativeEvidenceStore store,JdbcTemplate db) throws Exception {
        String sha=coverage.original().sha256();
        assertThat(sha).isEqualTo(HASHES.get(fmt));
        var ids=new HashMap<NativeLocator,String>();
        var indexed=db.queryForList("SELECT content FROM knowledge_chunk WHERE tenant_code=? AND index_version_id=? ORDER BY ordinal",
                String.class,tenant,index);
        assertThat(indexed).hasSize(coverage.locations().size());
        for(var row:anchorRows) {
            NativeLocator a=switch(fmt) {
                case "MD" -> new NativeLocator(2,NativeLocator.Kind.MD_LINES,"",row[1],row[1],null);
                case "XLSX" -> new NativeLocator(2,NativeLocator.Kind.XLSX_CELLS,row[2],row[3].split(":")[0],row[3].split(":")[1],null);
                case "DOCX" -> new NativeLocator(2,NativeLocator.Kind.WORD_BLOCKS,row[4],row[6],row[6],null);
                default -> throw new IllegalArgumentException(fmt);
            };
            assertThat(ids.put(a,row[0])).isNull();
            int ord=coverage.locations().indexOf(a);
            assertThat(ord).as("parse coverage %s %s",fmt,row[0]).isNotNegative();
            assertThat(indexed.get(ord)).contains(MARKERS.get(row[0]).toArray(String[]::new));
        }
        assertThat(ids.values()).containsExactlyInAnyOrderElementsOf(MARKERS.keySet());
        for(var e:expectations.values()) {
            assertThat(ids.values()).containsAll(e.relevanceJudgments().keySet());
            for(int k:List.of(3,5,10)){
                var result=service.searchForAgent(new RetrievalRequest(questions.get(e.caseId()),List.of(),List.of(),null,k,
                        RetrievalRequest.StrategyHint.AUTO,0,List.of()),tenant,version,"buyer");
                var cited=new HashMap<io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference,String>();
                for(var citation:result.citations()) {
                    var ref=citation.reference();
                    assertThat(ref.sourceId()).isEqualTo("approval."+fmt.toLowerCase(Locale.ROOT));
                    assertThat(ref.sourceVersion()).isEqualTo("1");
                    var preview=store.read(tenant,"buyer",result.retrievalTraceId(),ref.locator());
                    assertThat(preview.originalSha256()).isEqualTo(sha);
                    assertThat(preview.excerpt()).isEqualTo(citation.excerpt());
                    assertThat(preview.recipe()).isEqualTo(coverage.recipe());
                    assertThat(cited.put(ref,ids.getOrDefault(preview.anchor(),"@"+preview.anchor().start()))).isNull();
                }
                var refs=result.evidence().stream().map(hit->hit.reference()).toList();
                assertThat(refs).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(cited.keySet());
                var ranked=refs.stream().map(cited::get).toList();
                assertThat(ranked).doesNotContainNull().hasSize(cited.size());
                assertThat(ranked.size()).isLessThanOrEqualTo(k);
                var score=new RetrievalEvaluation().evaluateGroups(e.requiredEvidenceGroups(),e.relevanceJudgments(),e.forbiddenEvidence(),ranked,k);
                assertThat(score.leaks()).isEmpty();
                if ((k==3 && Set.of("B01","B02","B03","B09").contains(e.caseId())) || (k>=5 && e.caseId().equals("B04")))
                    assertThat(score.complete()).as("CNY interval and source companions: %s %s K=%s",fmt,e.caseId(),k).isTrue();
                if (k==3 && e.caseId().equals("B04"))
                    assertThat(score.complete()).as("Four independent anchors cannot fit three slots").isFalse();
                results.add(String.join("\t",fmt,e.caseId(),""+k,""+ranked.size(),""+score.matchedGroups(),
                        ""+e.requiredEvidenceGroups().size(),""+score.complete(),JSON.writeValueAsString(score.missing()),
                        JSON.writeValueAsString(ranked),""+score.unjudged(),
                        ""+e.citationExpectations().stream().filter(cited::containsValue).count(),""+e.citationExpectations().size(),
                        e.expectedDisposition().get("action"),"NOT_RUN"));
                traceLog.add(JSON.writeValueAsString(Map.ofEntries(Map.entry("tenant",tenant),Map.entry("principal","buyer"),
                        Map.entry("format",fmt),Map.entry("caseId",e.caseId()),Map.entry("k",k),Map.entry("agentVersionId",version),
                        Map.entry("profileId",profile),Map.entry("indexId",index),Map.entry("originalSha256",sha),Map.entry("recipe",coverage.recipe()),
                        Map.entry("strategy",result.strategy()),Map.entry("status",result.status()),Map.entry("traceId",result.retrievalTraceId()))));
            }
        }
    }
    void writeReport() throws Exception {
        assertThat(results).hasSize(82);
        var out=Files.createDirectories(Path.of("target","procurement-retrieval-baseline"));
        Files.write(out.resolve("results.tsv"),results);
        Files.write(out.resolve("traces.jsonl"),traceLog);
        var hashes=new TreeMap<String,String>();
        for(String name:List.of("cases.tsv","anchors.tsv","retrieval-expectations.dev.jsonl")) {
            // Normalize checkout line endings for text fixture fingerprints across CI and Windows.
            byte[] canonical=Files.readString(pilot.resolve(name)).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            hashes.put(name,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical)));
        }
        Files.writeString(out.resolve("run.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "dataset","procurement-native-retrieval-dev-v1","status","DRAFT","fixtureTextSha256",hashes,
                "sourceByteSha256",HASHES,"strategy","KEYWORD","lookupRecipe","native-cny-range-companions-v1","topK",List.of(3,5,10),
                "finishedAt",java.time.Instant.now().toString(),"modelAndFlowable","NOT_RUN")));
    }
}
