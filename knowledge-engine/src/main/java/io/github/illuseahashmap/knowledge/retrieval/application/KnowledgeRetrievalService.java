package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetriever;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeAccessPolicy;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetrievalUseCase;
import io.github.illuseahashmap.knowledge.retrieval.application.port.RetrievalProfileRepository;
import io.github.illuseahashmap.knowledge.retrieval.application.port.AgentRetrievalProfileBinding;
import io.github.illuseahashmap.knowledge.retrieval.application.port.NoopRetrievalTraceRepository;
import io.github.illuseahashmap.knowledge.retrieval.application.port.RetrievalTraceRepository;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalTrace;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceGroundingPolicy;
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
    private final RetrievalProfileRepository profileRepository;
    private final AgentRetrievalProfileBinding profileBinding;
    private final io.github.illuseahashmap.knowledge.retrieval.application.port.EvidenceVisibilityPort evidenceVisibility;
    private final EvidenceGroundingPolicy groundingPolicy = new EvidenceGroundingPolicy();

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
        this.profileRepository = null;
        this.profileBinding = null;
        this.evidenceVisibility = null;
    }

    public KnowledgeRetrievalService(
            TenantProvider tenantProvider, KnowledgeRetriever retriever,
            KnowledgeAccessPolicy accessPolicy, RetrievalTraceRepository traceRepository,
            RetrievalProfileRepository profileRepository,
            AgentRetrievalProfileBinding profileBinding) {
        this(tenantProvider, retriever, accessPolicy, traceRepository, profileRepository, profileBinding, null);
    }

    public KnowledgeRetrievalService(TenantProvider tenantProvider, KnowledgeRetriever retriever,
            KnowledgeAccessPolicy accessPolicy, RetrievalTraceRepository traceRepository,
            RetrievalProfileRepository profileRepository, AgentRetrievalProfileBinding profileBinding,
            io.github.illuseahashmap.knowledge.retrieval.application.port.EvidenceVisibilityPort evidenceVisibility) {
        this.tenantProvider = Objects.requireNonNull(tenantProvider, "tenantProvider must not be null");
        this.retriever = Objects.requireNonNull(retriever, "retriever must not be null");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
        this.traceRepository = Objects.requireNonNull(traceRepository, "traceRepository must not be null");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.profileBinding = Objects.requireNonNull(profileBinding, "profileBinding must not be null");
        this.evidenceVisibility = evidenceVisibility;
    }

    @Override
    public RetrievalResult search(RetrievalRequest request, long agentVersionId) {
        return search(request, agentVersionId, null);
    }

    @Override
    public void validateResultAccess(String tenantCode, long agentVersionId, String principalId,
            String traceId, java.util.List<io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference> references) {
        if (profileRepository == null || evidenceVisibility == null || principalId == null || principalId.isBlank()) {
            throw new IllegalStateException("Knowledge result access validation requires a trusted principal");
        }
        var profileId = profileBinding.findProfileVersionId(tenantCode, agentVersionId).orElseThrow();
        var profile = profileRepository.findPublished(tenantCode, profileId).orElseThrow();
        var trace = traceRepository.find(tenantCode, traceId).orElseThrow();
        var authorized = accessPolicy.authorize(tenantCode, principalId, profile.knowledgeScopes(), trace.authorizedScopes());
        if (trace.status() != io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalStatus.SUCCESS
                || !Objects.equals(trace.profileVersionId(), profileId)
                || references.isEmpty() || references.size() != trace.evidenceReferences().size()
                || !new java.util.HashSet<>(references).equals(new java.util.HashSet<>(trace.evidenceReferences()))
                || !authorized.containsAll(trace.authorizedScopes())
                || !evidenceVisibility.visible(tenantCode, authorized, references, profile.indexBindings().isEmpty()
                        ? java.util.List.of(profile.indexVersionId())
                        : profile.indexBindings().stream().map(binding -> binding.indexVersionId()).toList())) {
            throw new IllegalStateException("Knowledge evidence is no longer authorized or visible");
        }
    }

    @Override
    public RetrievalResult search(RetrievalRequest request, long agentVersionId, String principalId) {
        TenantContext.TenantInfo tenant = Objects.requireNonNull(
                tenantProvider.current(), "tenant context must not be null");
        return searchForAgent(request, tenant.tenantCode(), agentVersionId, principalId);
    }

    @Override
    public RetrievalResult searchForAgent(
            RetrievalRequest request, String tenantCode, long agentVersionId, String principalId) {
        if (agentVersionId < 1 || profileRepository == null) {
            throw new IllegalStateException("Published AgentVersion retrieval profile is required");
        }
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new IllegalStateException("Trusted AgentRun tenant code is required");
        }
        long profileVersionId = profileBinding.findProfileVersionId(tenantCode, agentVersionId)
                .orElseThrow(() -> new IllegalStateException("Published AgentVersion retrieval profile is required"));
        var profile = profileRepository.findPublished(tenantCode, profileVersionId)
                .orElseThrow(() -> new IllegalStateException("Published retrieval profile is not bound to AgentVersion"));
        RetrievalRequest profiled = new RetrievalRequest(request.query(),
                profile.knowledgeScopes(), request.filters(), request.asOfTime(),
                Math.min(request.maxResults(), profile.maxResults()),
                profile.strategy() == RetrievalRequest.StrategyHint.AUTO
                        ? RetrievalRequest.StrategyHint.KEYWORD : profile.strategy(),
                request.maxHops(), request.requiredEvidenceTypes(), profile.indexVersionId(), profile.indexBindings());
        return search(profiled, tenantCode, requestScopes(request, profile.knowledgeScopes()),
                principalId, profile.scorePolicy(), profile.id());
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
        return retrieveAuthorized(request, tenant.tenantCode(), authorizedScopes, null, null);
    }

    private RetrievalResult search(RetrievalRequest request, String tenantCode,
                                   java.util.List<String> requestedScopes, String principalId,
                                   io.github.illuseahashmap.knowledge.retrieval.domain.ScorePolicy scorePolicy, Long profileVersionId) {
        var authorizedScopes = accessPolicy.authorize(tenantCode, principalId,
                request.knowledgeScopes(), requestedScopes);
        return retrieveAuthorized(request, tenantCode, authorizedScopes, scorePolicy, profileVersionId);
    }

    private RetrievalResult retrieveAuthorized(RetrievalRequest request, String tenantCode,
                                               java.util.List<String> authorizedScopes,
                                               io.github.illuseahashmap.knowledge.retrieval.domain.ScorePolicy scorePolicy,
                                               Long profileVersionId) {
        if (authorizedScopes.isEmpty()) {
            throw new IllegalStateException("No authorized knowledge scope is available");
        }
        if (!request.filters().isEmpty() || request.asOfTime() != null) {
            throw new IllegalArgumentException("Metadata and historical retrieval filters are not implemented");
        }
        long started = System.nanoTime();
        RetrievalRequest authorizedRequest = request.withAuthorizedScopes(authorizedScopes);
        RetrievalResult raw = retriever.retrieve(tenantCode, authorizedRequest);
        var effectiveScorePolicy = scorePolicy == null
                ? io.github.illuseahashmap.knowledge.retrieval.domain.ScorePolicy.forStrategy(raw.strategy(), 0) : scorePolicy;
        RetrievalResult result = groundingPolicy.apply(raw, effectiveScorePolicy);
        traceRepository.save(new RetrievalTrace(
                result.retrievalTraceId(), tenantCode, fingerprint(request.query()), authorizedScopes,
                result.status(), result.strategy(), raw.evidence().size(), Instant.now(),
                profileVersionId, authorizedRequest.frozenIndexIds().size() == 1
                        ? authorizedRequest.frozenIndexIds().getFirst() : null,
                result.evidence().stream().map(evidence -> evidence.reference()).toList(),
                java.time.Duration.ofNanos(System.nanoTime() - started).toMillis(), authorizedRequest.indexBindings()));
        return result;
    }

    private java.util.List<String> requestScopes(RetrievalRequest request, java.util.List<String> profileScopes) {
        if (request.knowledgeScopes().isEmpty()) {
            return profileScopes;
        }
        return request.knowledgeScopes().stream().filter(profileScopes::contains).toList();
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
