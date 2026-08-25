package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetriever;
import io.github.illuseahashmap.knowledge.retrieval.domain.ChunkEvidence;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceType;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalResult;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalStatus;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeRetrievalServiceTest {

    @Test
    void passesTrustedTenantToRetriever() {
        String[] capturedTenant = new String[1];
        KnowledgeRetriever retriever = (tenantCode, request) -> {
            capturedTenant[0] = tenantCode;
            assertThat(request.knowledgeScopes()).containsExactly("allowed");
            EvidenceReference reference = new EvidenceReference(
                    EvidenceType.CHUNK, "doc-1", "v1", "chunk-1");
            ChunkEvidence evidence = new ChunkEvidence(reference, "approved", 1.0, "Policy");
            return new RetrievalResult(RetrievalStatus.SUCCESS, List.of(evidence), List.of(),
                    "trace-1", "KEYWORD", List.of(), false);
        };
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(
                () -> new TenantContext.TenantInfo("tenant-id", "tenant-a", "Tenant A"), retriever,
                (tenantCode, requestedScopes) -> List.of("allowed"));

        RetrievalResult result = service.search(new RetrievalRequest(
                "approval", List.of("policy"), List.of(), null, 5,
                RetrievalRequest.StrategyHint.AUTO, 0, List.of(EvidenceType.CHUNK)));

        assertThat(capturedTenant[0]).isEqualTo("tenant-a");
        assertThat(result.status()).isEqualTo(RetrievalStatus.SUCCESS);
    }
}
