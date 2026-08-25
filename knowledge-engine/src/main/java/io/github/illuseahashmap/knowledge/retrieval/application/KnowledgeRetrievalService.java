package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetriever;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetrievalUseCase;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalResult;
import io.github.illuseahashmap.workflow.shared.context.TenantContext;
import io.github.illuseahashmap.workflow.shared.context.TenantProvider;

import java.util.Objects;

/** Application service that obtains tenant identity from trusted context only. */
public final class KnowledgeRetrievalService implements KnowledgeRetrievalUseCase {

    private final TenantProvider tenantProvider;
    private final KnowledgeRetriever retriever;

    public KnowledgeRetrievalService(TenantProvider tenantProvider, KnowledgeRetriever retriever) {
        this.tenantProvider = Objects.requireNonNull(tenantProvider, "tenantProvider must not be null");
        this.retriever = Objects.requireNonNull(retriever, "retriever must not be null");
    }

    @Override
    public RetrievalResult search(RetrievalRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        TenantContext.TenantInfo tenant = Objects.requireNonNull(
                tenantProvider.current(), "tenant context must not be null");
        if (tenant.tenantCode() == null || tenant.tenantCode().isBlank()) {
            throw new IllegalStateException("Tenant code is required for knowledge retrieval");
        }
        return retriever.retrieve(tenant.tenantCode(), request);
    }
}
