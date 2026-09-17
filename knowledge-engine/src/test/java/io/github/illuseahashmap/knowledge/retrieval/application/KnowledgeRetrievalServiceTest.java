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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void appliesPublishedProfileBeforeRetrievalAndDoesNotTrustModelScopes() {
        String[] capturedScopes = new String[1];
        KnowledgeRetriever retriever = (tenantCode, request) -> {
            capturedScopes[0] = String.join(",", request.knowledgeScopes());
            return new RetrievalResult(RetrievalStatus.EMPTY, List.of(), List.of(),
                    "trace-profile", "KEYWORD", List.of(), true);
        };
        var profile = new io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalProfileVersion(
                9, "tenant-a", "policies", 1, RetrievalRequest.StrategyHint.KEYWORD,
                3, 0, 2, "PUBLISHED", List.of("policy.read"), Instant.EPOCH);
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(
                () -> new TenantContext.TenantInfo("tenant-id", "tenant-a", "Tenant A"), retriever,
                (tenantCode, requestedScopes) -> requestedScopes,
                new io.github.illuseahashmap.knowledge.retrieval.application.port.NoopRetrievalTraceRepository(),
                (tenantCode, profileVersionId) -> java.util.Optional.of(profile),
                (tenantCode, agentVersionId) -> java.util.Optional.of(9L));

        service.search(new RetrievalRequest("approval", List.of("model-controlled"), List.of(), null, 20,
                RetrievalRequest.StrategyHint.AUTO, 0, List.of()), 42);

        assertThat(capturedScopes[0]).isEqualTo("policy.read");
    }

    @Test
    void rejectsAgentVersionWithoutProfileBinding() {
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(
                () -> new TenantContext.TenantInfo("tenant-id", "tenant-a", "Tenant A"),
                (tenantCode, request) -> { throw new AssertionError("retriever must not be called"); },
                (tenantCode, requestedScopes) -> requestedScopes,
                new io.github.illuseahashmap.knowledge.retrieval.application.port.NoopRetrievalTraceRepository(),
                (tenantCode, profileVersionId) -> java.util.Optional.empty(),
                (tenantCode, agentVersionId) -> java.util.Optional.empty());

        assertThatThrownBy(() -> service.search(new RetrievalRequest("approval", List.of(), List.of(),
                null, 5, RetrievalRequest.StrategyHint.AUTO, 0, List.of()), 42))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retrieval profile");
    }

    @Test
    void agentWorkerUsesExplicitTrustedTenantWithoutThreadLocalContext() {
        String[] capturedTenant = new String[1];
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(
                () -> { throw new AssertionError("worker must not read HTTP tenant context"); },
                (tenantCode, request) -> {
                    capturedTenant[0] = tenantCode;
                    return new RetrievalResult(RetrievalStatus.EMPTY, List.of(), List.of(),
                            "trace-worker", "KEYWORD", List.of(), true);
                },
                (tenantCode, requestedScopes) -> requestedScopes,
                new io.github.illuseahashmap.knowledge.retrieval.application.port.NoopRetrievalTraceRepository(),
                (tenantCode, profileVersionId) -> java.util.Optional.of(
                        new io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalProfileVersion(
                                9, tenantCode, "policies", 1, RetrievalRequest.StrategyHint.KEYWORD,
                                3, 0, 2, "PUBLISHED", List.of("policy.read"), Instant.EPOCH)),
                (tenantCode, agentVersionId) -> java.util.Optional.of(9L));

        service.searchForAgent(new RetrievalRequest("approval", List.of(), List.of(), null, 5,
                RetrievalRequest.StrategyHint.AUTO, 0, List.of()), "tenant-a", 42, "admin");

        assertThat(capturedTenant[0]).isEqualTo("tenant-a");
    }
}
