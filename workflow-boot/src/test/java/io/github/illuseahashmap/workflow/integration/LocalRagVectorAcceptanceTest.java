package io.github.illuseahashmap.workflow.integration;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeManagementPort;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetriever;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceType;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalStatus;
import io.github.illuseahashmap.workflow.WorkflowAgentServiceApplication;
import io.github.illuseahashmap.workflow.shared.context.TrustedDataAccessContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in acceptance against the local PostgreSQL database and the configured Ark embedding model. */
@SpringBootTest(classes = WorkflowAgentServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"local", "vector"})
@EnabledIfEnvironmentVariable(named = "WORKFLOW_RUN_LOCAL_RAG_ACCEPTANCE", matches = "true")
class LocalRagVectorAcceptanceTest {
    private static final String TENANT = "default";
    private static final String SOURCE = "company-handbook";
    private static final String DOCUMENT = "employee-handbook.md";

    @Autowired KnowledgeManagementPort management;
    @Autowired KnowledgeRetriever retriever;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test
    void reingestsExistingHandbookWithRealVectorsAndRetrievesCitedEvidence() throws Exception {
        Path fixture = Path.of("..", "docs", "fixtures", "knowledge", DOCUMENT).toAbsolutePath().normalize();
        byte[] bytes = Files.readAllBytes(fixture);
        String content = new String(bytes, StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Integer documentCount = TrustedDataAccessContext.runAsSystemWorker(() -> jdbc.queryForObject("""
                SELECT count(*) FROM knowledge_document_version
                WHERE tenant_code = :tenant AND source_code = :source
                  AND external_document_id = :document AND version = 1 AND content_hash = :hash
                """, new MapSqlParameterSource().addValue("tenant", TENANT).addValue("source", SOURCE)
                .addValue("document", DOCUMENT).addValue("hash", hash), Integer.class));
        assertThat(documentCount).isEqualTo(1);

        var active = activeIndex();
        if ("none".equals(active.embeddingModel())) {
            // Re-index the already stored document; do not invent a new document version.
            var document = TrustedDataAccessContext.runAsSystemWorker(() -> management.createDocument(TENANT,
                    new KnowledgeManagementPort.CreateDocument(SOURCE, "员工工作手册", DOCUMENT, 1, hash, content)));
            String status = null;
            for (int attempt = 0; attempt < 90; attempt++) {
                status = TrustedDataAccessContext.runAsSystemWorker(() -> management
                        .ingestionJobs(TENANT, 1, 20, null).records().stream()
                        .filter(job -> job.id() == document.ingestionJobId())
                        .map(KnowledgeManagementPort.JobView::status).findFirst().orElse(null));
                if ("SUCCEEDED".equals(status) || "FAILED".equals(status)) {
                    break;
                }
                Thread.sleep(1000);
            }
            assertThat(status).as("ingestion job %s", document.ingestionJobId()).isEqualTo("SUCCEEDED");
            active = activeIndex();
        }
        assertThat(active.embeddingModel()).isNotEqualTo("none");
        long activeIndexId = active.id();
        int vectorCount = TrustedDataAccessContext.runAsSystemWorker(() -> jdbc.queryForObject("""
                SELECT count(*) FROM knowledge_chunk_embedding
                WHERE tenant_code = :tenant AND index_version_id = :indexId AND dimension = 1024
                """, new MapSqlParameterSource().addValue("tenant", TENANT)
                .addValue("indexId", activeIndexId), Integer.class));
        assertThat(vectorCount).isPositive();

        var request = new RetrievalRequest("采购金额超过2000元但不超过20000元需要谁审批？",
                List.of(SOURCE), List.of(), Instant.now(), 5,
                RetrievalRequest.StrategyHint.HYBRID, 0, List.of(EvidenceType.CHUNK), active.id());
        var result = TrustedDataAccessContext.runAsSystemWorker(() -> retriever.retrieve(TENANT, request));
        assertThat(result.status()).isEqualTo(RetrievalStatus.SUCCESS);
        assertThat(result.citations()).isNotEmpty()
                .allSatisfy(citation -> assertThat(citation.reference().sourceId()).isEqualTo(DOCUMENT));
        assertThat(result.evidence()).anySatisfy(evidence -> assertThat(evidence.toString())
                .contains("直属负责人", "部门负责人"));

        var frozenKeywordRequest = new RetrievalRequest(request.query(), List.of(SOURCE), List.of(),
                Instant.now(), 5, RetrievalRequest.StrategyHint.KEYWORD, 0,
                List.of(EvidenceType.CHUNK), 1);
        var frozenKeyword = TrustedDataAccessContext.runAsSystemWorker(
                () -> retriever.retrieve(TENANT, frozenKeywordRequest));
        assertThat(frozenKeyword.status()).isEqualTo(RetrievalStatus.SUCCESS);
        assertThat(frozenKeyword.citations()).isNotEmpty();
    }

    private KnowledgeManagementPort.IndexView activeIndex() {
        return TrustedDataAccessContext.runAsSystemWorker(() -> management
                .indexVersions(TENANT, "ACTIVE").stream()
                .filter(index -> SOURCE.equals(index.sourceCode())).findFirst().orElseThrow());
    }
}
