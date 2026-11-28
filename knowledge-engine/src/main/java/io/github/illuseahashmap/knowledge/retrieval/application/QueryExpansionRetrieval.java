package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetriever;
import io.github.illuseahashmap.knowledge.retrieval.domain.ScorePolicy;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceScore;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceGroundingPolicy;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalResult;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.QueryExpansionTrace;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalStatus;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference;
import io.github.illuseahashmap.knowledge.retrieval.domain.Evidence;
import io.github.illuseahashmap.knowledge.retrieval.domain.ChunkEvidence;
import io.github.illuseahashmap.knowledge.retrieval.domain.Citation;
import io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelException;
import io.github.illuseahashmap.workflow.shared.context.ExecutionDeadline;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.Locale;
import java.util.HexFormat;

/** Two sequential authorized lanes. No model call, query synthesis, or corpus-specific rules. */
final class QueryExpansionRetrieval {
    private static final ScorePolicy FUSION = new ScorePolicy(EvidenceScore.Kind.RRF_NORMALIZED, "query-rrf-k60-v1", 0);
    private final KnowledgeRetriever retriever;
    private final EvidenceGroundingPolicy gate = new EvidenceGroundingPolicy();
    QueryExpansionRetrieval(KnowledgeRetriever retriever) { this.retriever = retriever; }
    record Outcome(RetrievalResult result, QueryExpansionTrace trace) {}

    Outcome retrieve(String tenant, RetrievalRequest request, ScorePolicy policy) {
        var lanes = new ArrayList<QueryExpansionTrace.Lane>();
        var main = lane(tenant, request, policy, lanes);
        if (request.auxiliaryQueries().isEmpty()) { return outcome(main, "NO_AUXILIARY", lanes); }
        String auxiliary = request.auxiliaryQueries().getFirst().trim();
        if (normalized(auxiliary).equals(normalized(request.query()))) { return outcome(main, "DUPLICATE_AUXILIARY", lanes); }
        if (!io.github.illuseahashmap.knowledge.retrieval.domain.AuxiliaryQueryPolicy.isTopicOnly(auxiliary)) {
            return outcome(main, "AUXILIARY_REJECTED_CONDITIONS_TOPIC_V1", lanes);
        }
        // Never weaken incomplete evidence obligations, score rejection, or failure of the main lane.
        if (main.status() != RetrievalStatus.SUCCESS && main.status() != RetrievalStatus.EMPTY) {
            return outcome(main, "MAIN_NOT_ELIGIBLE", lanes);
        }
        var remaining = ExecutionDeadline.remainingOrNull();
        if (remaining != null && remaining.compareTo(Duration.ofMillis(500)) < 0) {
            return outcome(main, "AUXILIARY_SKIPPED_DEADLINE", lanes);
        }
        RetrievalResult other;
        // Main lane has already finished; reserve half the remaining budget for downstream processing.
        Duration auxiliaryBudget = remaining == null ? Duration.ofSeconds(2)
                : ExecutionDeadline.minimum(Duration.ofSeconds(2), remaining.dividedBy(2));
        try (var ignored = ExecutionDeadline.open(auxiliaryBudget, System::nanoTime)) {
            other = lane(tenant, new RetrievalRequest(auxiliary, request.knowledgeScopes(), request.filters(),
                    request.asOfTime(), request.maxResults(), request.strategyHint(), request.maxHops(),
                    request.requiredEvidenceTypes(), request.indexVersionId(), request.indexBindings()), policy, lanes);
            ExecutionDeadline.remainingOrNull();
        } catch (ExecutionDeadline.Exceeded expired) {
            ExecutionDeadline.remainingOrNull(); // A total deadline is not a recoverable lane failure.
            return outcome(main, "AUXILIARY_TIMEOUT", lanes);
        } catch (EmbeddingModelException failure) {
            if (!failure.retryable() || Thread.currentThread().isInterrupted()) { throw failure; }
            ExecutionDeadline.remainingOrNull();
            return outcome(main, "AUXILIARY_PROVIDER_TRANSIENT", lanes);
        }
        ExecutionDeadline.remainingOrNull();
        if (other.status() != RetrievalStatus.SUCCESS || other.abstained()) {
            return outcome(main, "AUXILIARY_" + other.status().name(), lanes);
        }
        return outcome(fuse(main, other, request.maxResults()), "FUSED", lanes);
    }

    private RetrievalResult lane(String tenant, RetrievalRequest request, ScorePolicy policy,
                                 List<QueryExpansionTrace.Lane> lanes) {
        if (Thread.currentThread().isInterrupted()) { throw new java.util.concurrent.CancellationException("Retrieval interrupted"); }
        ExecutionDeadline.remainingOrNull();
        long started = System.nanoTime();
        try {
            var raw = retriever.retrieve(tenant, request);
            if (Thread.currentThread().isInterrupted()) { throw new java.util.concurrent.CancellationException("Retrieval interrupted"); }
            ExecutionDeadline.remainingOrNull();
            var result = gate.apply(raw, policy);
            lanes.add(new QueryExpansionTrace.Lane(fingerprint(request.query()), result.retrievalTraceId(),
                    result.status().name(), Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    result.evidence().stream().map(Evidence::reference).toList()));
            return result;
        } catch (RuntimeException failure) {
            lanes.add(new QueryExpansionTrace.Lane(fingerprint(request.query()), "", "FAILED",
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), List.of()));
            throw failure;
        }
    }

    private Outcome outcome(RetrievalResult result, String outcome, List<QueryExpansionTrace.Lane> lanes) {
        return new Outcome(result, new QueryExpansionTrace("RRF_V1", outcome, lanes));
    }

    private RetrievalResult fuse(RetrievalResult main, RetrievalResult other, int limit) {
        var chunks = new LinkedHashMap<EvidenceReference, ChunkEvidence>();
        var scores = new HashMap<EvidenceReference, Double>();
        for (var lane : List.of(main, other)) {
            var seen = new HashSet<EvidenceReference>();
            int rank = 0;
            for (var evidence : lane.evidence()) {
                if (!seen.add(evidence.reference())) { continue; }
                rank++;
                var chunk = (ChunkEvidence) evidence;
                chunks.putIfAbsent(chunk.reference(), chunk);
                scores.merge(chunk.reference(), 1.0 / (60 + rank), Double::sum);
            }
        }
        // Stable insertion order breaks ties in favor of main; distinct scales are never added.
        List<Evidence> selected = chunks.values().stream()
                .sorted(Comparator.comparingDouble((ChunkEvidence c) -> scores.get(c.reference())).reversed())
                .limit(limit).map(c -> (Evidence) new ChunkEvidence(c.reference(), c.text(),
                        scores.get(c.reference()) * 61 / 2, c.title(), FUSION.kind(), FUSION.version())).toList();
        var citations = new ArrayList<Citation>();
        for (var evidence : selected) {
            citations.add(new Citation("q" + (citations.size() + 1), evidence.reference(), ((ChunkEvidence) evidence).text()));
        }
        var obligations = new ArrayList<List<EvidenceReference>>(main.requiredEvidenceGroups());
        obligations.addAll(other.requiredEvidenceGroups());
        return gate.apply(new RetrievalResult(RetrievalStatus.SUCCESS, selected, citations,
                main.retrievalTraceId(), "QUERY_RRF", List.of(), false, obligations), FUSION);
    }

    private static String normalized(String value) { return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); }
    static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
