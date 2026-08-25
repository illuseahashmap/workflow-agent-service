package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetriever;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeAccessPolicy;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetrievalUseCase;
import io.github.illuseahashmap.knowledge.retrieval.application.port.NoopRetrievalTraceRepository;
import io.github.illuseahashmap.knowledge.retrieval.application.port.RetrievalTraceRepository;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalTrace;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalResult;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;

import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Application service that obtains tenant identity from trusted context only. */
public final class KnowledgeRetrievalService implements KnowledgeRetrievalUseCase {

    private final TenantProvider tenantProvider;
    private final KnowledgeRetriever retriever;
    private final KnowledgeAccessPolicy accessPolicy;
    private final RetrievalTraceRepository traceRepository;

    public KnowledgeRetrievalService(
            TenantProvider tenantProvider,
            KnowledgeRetriever retriever,
            KnowledgeAccessPolicy accessPolicy
    ) {
        this(tenantProvider, retriever, accessPolicy, new NoopRetrievalTraceRepository());
    }

    public KnowledgeRetrievalService(
            TenantProvider tenantProvider,
            KnowledgeRetriever retriever,
            KnowledgeAccessPolicy accessPolicy,
            RetrievalTraceRepository traceRepository
    ) {
        this.tenantProvider = Objects.requireNonNull(tenantProvider, "tenantProvider must not be null");
        this.retriever = Objects.requireNonNull(retriever, "retriever must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.traceRepository = Objects.requireNonNull(traceRepository, "traceRepository must not be null");
    }

    @Override
    public RetrievalResult search(RetrievalRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        TenantContext.TenantInfo tenant = Objects.requireNonNull(
                tenantProvider.current(), "tenant context must not be null");
        if (tenant.tenantCode() == null || tenant.tenantCode().isBlank()) {
            throw new IllegalStateException("Tenant code is required for knowledge retrieval");
        }
        var authorizedScopes = accessPolicy.authorize(tenant.tenantCode(), request.knowledgeScopes());
        RetrievalResult result = retriever.retrieve(tenant.tenantCode(), request.withAuthorizedScopes(authorizedScopes));
        traceRepository.save(new RetrievalTrace(
                result.retrievalTraceId(), tenant.tenantCode(), fingerprint(request.query()), authorizedScopes,
                result.status(), result.strategy(), result.evidence().size(), Instant.now()));
        return result;
    }

    private String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to fingerprint retrieval query", exception);
        }
    }
}
