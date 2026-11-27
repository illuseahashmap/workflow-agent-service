package io.github.illuseahashmap.knowledge.retrieval.application;

import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeAccessPolicy;
import io.github.illuseahashmap.knowledge.retrieval.application.port.RetrievalTraceRepository;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceType;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalProfileVersion;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalRequest;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalStatus;
import io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalTrace;
import io.github.illuseahashmap.knowledge.retrieval.domain.SourceIndexBinding;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeResultAccessTest {
    @Test
    void revalidatesScopeDocumentStateAndTraceProvenanceWithoutRepeatingRetrieval() {
        var granted = new AtomicBoolean(true);
        var visible = new AtomicBoolean(true);
        var reference = new EvidenceReference(EvidenceType.CHUNK, "policy", "1", "chunk");
        var profile = new RetrievalProfileVersion(9, "tenant-a", "policy", 1, RetrievalRequest.StrategyHint.KEYWORD,
                5, 0, 7, "PUBLISHED", List.of("policies"), Instant.EPOCH,
                List.of(new SourceIndexBinding("policies", 7)));
        var trace = new RetrievalTrace("trace", "tenant-a", "query", List.of("policies"), RetrievalStatus.SUCCESS,
                "KEYWORD", 1, Instant.EPOCH, 9L, 7L, List.of(reference), 1);
        var traces = new RetrievalTraceRepository() {
            public void save(RetrievalTrace ignored) { throw new AssertionError("Replay must not create another trace"); }
            public Optional<RetrievalTrace> find(String tenant, String id) {
                return "tenant-a".equals(tenant) && "trace".equals(id) ? Optional.of(trace) : Optional.empty();
            }
        };
        var access = new KnowledgeAccessPolicy() {
            public List<String> authorize(String tenant, List<String> scopes) { return List.of(); }
            public List<String> authorize(String tenant, String principal, List<String> scopes, List<String> requested) {
                return granted.get() && "user".equals(principal) ? scopes : List.of();
            }
        };
        var service = new KnowledgeRetrievalService(() -> { throw new AssertionError("No HTTP context"); },
                (tenant, request) -> { throw new AssertionError("Must not repeat retrieval"); }, access, traces,
                (tenant, id) -> Optional.of(profile), (tenant, id) -> Optional.of(9L),
                (tenant, scopes, refs, indexes) -> visible.get() && indexes.equals(List.of(7L)));
        assertThatCode(() -> service.validateResultAccess("tenant-a", 1, "user", "trace", List.of(reference)))
                .doesNotThrowAnyException();
        granted.set(false);
        assertThatThrownBy(() -> service.validateResultAccess("tenant-a", 1, "user", "trace", List.of(reference)))
                .hasMessageContaining("no longer authorized");
        granted.set(true);
        visible.set(false);
        assertThatThrownBy(() -> service.validateResultAccess("tenant-a", 1, "user", "trace", List.of(reference)))
                .hasMessageContaining("no longer authorized");
        visible.set(true);
        assertThatThrownBy(() -> service.validateResultAccess("tenant-a", 1, "user", "trace", List.of(
                new EvidenceReference(EvidenceType.CHUNK, "other", "1", "forged"))))
                .hasMessageContaining("no longer authorized");
    }
}
