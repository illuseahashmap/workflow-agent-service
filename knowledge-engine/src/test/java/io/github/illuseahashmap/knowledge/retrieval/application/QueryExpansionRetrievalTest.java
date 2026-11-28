package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.domain.ChunkEvidence;
import io.github.illuseahashmap.knowledge.retrieval.domain.Citation;
import io.github.illuseahashmap.knowledge.retrieval.domain.Evidence;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceType;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalResult;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalStatus;
import io.github.illuseahashmap.knowledge.retrieval.domain.ScorePolicy;
import io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelException;
import io.github.illuseahashmap.workflow.shared.context.ExecutionDeadline;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryExpansionRetrievalTest {
    private static final ScorePolicy POLICY = ScorePolicy.forStrategy("KEYWORD", 0.2);
    private RetrievalRequest request(String auxiliary, int k) {
        return new RetrievalRequest("original", List.of("allowed"), List.of(), null, k,
                RetrievalRequest.StrategyHint.KEYWORD, 0, List.of(), 7, List.of(),
                auxiliary == null ? List.of() : List.of(auxiliary));
    }
    private RetrievalResult result(String trace, double score, String... ids) {
        List<Evidence> evidence = java.util.Arrays.stream(ids).map(id -> (Evidence) ChunkEvidence.scored(
                new EvidenceReference(EvidenceType.CHUNK, "doc", "1", id), "body " + id, score, "title", "KEYWORD")).toList();
        return new RetrievalResult(RetrievalStatus.SUCCESS, evidence,
                evidence.stream().map(e -> new Citation(e.reference().locator(), e.reference(), "body")).toList(),
                trace, "KEYWORD", List.of(), false);
    }
    @Test void singleAndDuplicateDoNotIssueAnotherRetrieval() {
        var calls = new AtomicInteger();
        var expected = result("main", 0.8, "a");
        var expansion = new QueryExpansionRetrieval((t, r) -> { calls.incrementAndGet();
            return expected; });
        assertThat(expansion.retrieve("tenant", request(null, 2), POLICY).result()).isEqualTo(expected);
        assertThat(expansion.retrieve("tenant", request(" ORIGINAL ", 2), POLICY).trace().outcome()).isEqualTo("DUPLICATE_AUXILIARY");
        assertThat(calls).hasValue(2);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"below 900", "大于五万元", "仅主管审批", "not eligible", "x < y"})
    void unsafeAuxiliaryNeverReachesRetrieverAndMainResultRemainsUnchanged(String auxiliary) {
        var calls = new AtomicInteger();
        var expected = result("main", 0.8, "a");
        var expansion = new QueryExpansionRetrieval((t, r) -> {
            calls.incrementAndGet();
            assertThat(r.query()).isEqualTo("original");
            return expected;
        });
        var outcome = expansion.retrieve("tenant", request(auxiliary, 2), POLICY);
        assertThat(outcome.result()).isEqualTo(expected);
        assertThat(outcome.trace().outcome()).isEqualTo("AUXILIARY_REJECTED_CONDITIONS_TOPIC_V1");
        assertThat(calls).hasValue(1);
    }

    @Test void rankFusionDeduplicatesCapsAndPreservesFrozenAuthorization() {
        var expansion = new QueryExpansionRetrieval((tenant, r) -> {
            assertThat(tenant).isEqualTo("tenant");
            assertThat(r.knowledgeScopes()).containsExactly("allowed");
            assertThat(r.frozenIndexIds()).containsExactly(7L);
            assertThat(r.maxResults()).isEqualTo(2);
            return r.query().equals("original") ? result("main", 0.3, "a", "b") : result("aux", 999, "b", "c");
        });
        var outcome = expansion.retrieve("tenant", request("complement", 2), POLICY);
        assertThat(outcome.result().evidence()).extracting(e -> e.reference().locator()).containsExactly("b", "a");
        assertThat(outcome.result().citations()).extracting(c -> c.reference().locator()).containsExactly("b", "a");
        assertThat(outcome.result().strategy()).isEqualTo("QUERY_RRF");
        assertThat(outcome.trace().lanes()).hasSize(2);
        assertThat(outcome.trace().lanes().getFirst().queryFingerprint()).doesNotContain("original");
    }
    @Test void rejectsBelowThresholdAndNeverAddsIncompatibleScores() {
        var expansion = new QueryExpansionRetrieval((t, r) -> r.query().equals("original")
                ? result("main", 0.8, "a") : result("aux", 0.01, "b"));
        var outcome = expansion.retrieve("tenant", request("complement", 2), POLICY);
        assertThat(outcome.trace().outcome()).isEqualTo("AUXILIARY_REJECTED");
        assertThat(outcome.result().evidence()).extracting(e -> e.reference().locator()).containsExactly("a");
    }
    @Test void auxiliaryCannotHideMissingPrimaryObligations() {
        var calls = new AtomicInteger();
        var partial = new RetrievalResult(RetrievalStatus.PARTIAL, List.of(), List.of(), "main", "KEYWORD",
                List.of(), true, List.of(List.of()));
        var expansion = new QueryExpansionRetrieval((t, r) -> { calls.incrementAndGet();
            return partial; });
        assertThat(expansion.retrieve("tenant", request("complement", 2), POLICY).result()).isEqualTo(partial);
        assertThat(calls).hasValue(1);
    }
    @Test void cappingFusedEvidenceRetainsCoverageObligations() {
        var expansion = new QueryExpansionRetrieval((t, r) -> {
            var raw = result(r.query(), 0.8, r.query());
            return new RetrievalResult(raw.status(), raw.evidence(), raw.citations(), raw.retrievalTraceId(), raw.strategy(),
                    List.of(), false, List.of(List.of(raw.evidence().getFirst().reference())));
        });
        assertThat(expansion.retrieve("tenant", request("complement", 1), POLICY).result().status()).isEqualTo(RetrievalStatus.PARTIAL);
    }
    @Test void emptyPrimaryMayBeRecoveredByGroundedAuxiliary() {
        var expansion = new QueryExpansionRetrieval((t, r) -> r.query().equals("original")
                ? RetrievalResult.empty("main", "KEYWORD") : result("aux", 0.8, "a"));
        assertThat(expansion.retrieve("tenant", request("complement", 2), POLICY).result().status()).isEqualTo(RetrievalStatus.SUCCESS);
    }
    @Test void onlyRecoverableAuxiliaryErrorsFallBack() {
        var expected = result("main", 0.8, "a");
        var expansion = new QueryExpansionRetrieval((t, r) -> {
            if (r.query().equals("original")) { return expected; }
            throw new EmbeddingModelException("TEMPORARY", true);
        });
        assertThat(expansion.retrieve("tenant", request("complement", 2), POLICY).result()).isEqualTo(expected);
        var forbidden = new QueryExpansionRetrieval((t, r) -> {
            if (r.query().equals("original")) { return expected; }
            throw new IllegalStateException("authorization revoked");
        });
        assertThatThrownBy(() -> forbidden.retrieve("tenant", request("complement", 2), POLICY))
                .hasMessage("authorization revoked");
    }
    @Test void interruptedAuxiliaryIsNeverReturnedAsSuccessfulFallback() {
        var expansion = new QueryExpansionRetrieval((t, r) -> {
            if (r.query().equals("original")) { return result("main", 0.8, "a"); }
            Thread.currentThread().interrupt();
            throw new EmbeddingModelException("INTERRUPTED", true);
        });
        try {
            assertThatThrownBy(() -> expansion.retrieve("tenant", request("complement", 2), POLICY))
                    .isInstanceOf(EmbeddingModelException.class).hasMessage("INTERRUPTED");
        } finally { Thread.interrupted(); }
    }

    @Test void expiredTotalDeadlineIsNeverFallbackAndLowRemainingBudgetSkipsAuxiliary() {
        var clock = new AtomicLong();
        var calls = new AtomicInteger();
        var expansion = new QueryExpansionRetrieval((t, r) -> {
            calls.incrementAndGet();
            clock.set(Duration.ofSeconds(2).toNanos());
            return result("main", 0.8, "a");
        });
        try (var ignored = ExecutionDeadline.open(Duration.ofSeconds(1), clock::get)) {
            assertThatThrownBy(() -> expansion.retrieve("tenant", request("complement", 2), POLICY))
                    .isInstanceOf(ExecutionDeadline.Exceeded.class);
        }
        assertThat(calls).hasValue(1);
        try (var ignored = ExecutionDeadline.open(Duration.ofMillis(100), () -> 0)) {
            assertThat(expansion.retrieve("tenant", request("complement", 2), POLICY).trace().outcome())
                    .isEqualTo("AUXILIARY_SKIPPED_DEADLINE");
        }
    }
}
