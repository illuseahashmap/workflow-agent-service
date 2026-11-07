package io.github.illuseahashmap.workflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.runtime.application.AgentExecutionException;
import io.github.illuseahashmap.agent.runtime.application.port.AgentTool;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory;
import io.github.illuseahashmap.knowledge.ingestion.application.port.EmbeddingModelException;
import io.github.illuseahashmap.knowledge.retrieval.application.port.KnowledgeRetrievalUseCase;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeSearchAgentToolTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {2, 4})
    void registryDeliversLongEvidenceWholeOrReturnsExplicitInsufficiency(int count) throws Exception {
        var json = new ObjectMapper();
        var hits = new java.util.ArrayList<io.github.illuseahashmap.knowledge.retrieval.domain.Evidence>();
        var citations = new java.util.ArrayList<io.github.illuseahashmap.knowledge.retrieval.domain.Citation>();
        for (int i=0; i<count; i++) {
            var ref = new io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceReference(
                    io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceType.CHUNK, "policy", "1", "block-"+i);
            String body = "甲".repeat(6000)+"必要时提交委员会"+i;
            hits.add(new io.github.illuseahashmap.knowledge.retrieval.domain.ChunkEvidence(ref, body, 0.5, "采购"));
            citations.add(new io.github.illuseahashmap.knowledge.retrieval.domain.Citation("cite-"+i, ref, body));
        }
        var retrieval = mock(KnowledgeRetrievalUseCase.class);
        when(retrieval.searchForAgent(any(),eq("t"),eq(14L),eq("buyer"))).thenReturn(
                new io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalResult(
                        io.github.illuseahashmap.knowledge.retrieval.domain.RetrievalStatus.SUCCESS, hits, citations, "trace",
                        "KEYWORD", java.util.List.of(), false));
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("retrieval",retrieval);
        var registry = new io.github.illuseahashmap.agent.runtime.application.AgentToolRegistry(java.util.List.of(
                new KnowledgeSearchAgentTool(beans.getBeanProvider(KnowledgeRetrievalUseCase.class),json)));
        var request = new AgentTool.Request("t",Map.of("query","采购"),Duration.ofSeconds(5),
                "trace","step",null,1,"tool:1",14L,"buyer");
        java.util.function.Supplier<String> call = () -> registry.execute("t","[\"knowledge_search\"]",null,"knowledge_search",request).output();
        if (count==2) {
            String output = call.get();
            assertThat(output.length()).isLessThanOrEqualTo(AgentTool.MAX_OUTPUT_CHARS);
            for (int i=0;i<count;i++) assertThat(output).containsOnlyOnce(citations.get(i).excerpt());
        } else {
            assertThatThrownBy(call::get).isInstanceOfSatisfying(AgentExecutionException.class,
                    ex -> assertThat(ex.failure().errorCode()).isEqualTo("KNOWLEDGE_EVIDENCE_BUDGET_EXCEEDED"));
        }
    }
    @Test
    void transientEmbeddingFailureBecomesRetryableAgentFailure() {
        assertFailure(new EmbeddingModelException("ARK_EMBEDDING_TIMEOUT", true),
                AgentFailureCategory.PROVIDER_TRANSIENT, true);
    }

    @Test
    void authenticationFailureBecomesPermanentAgentFailure() {
        assertFailure(new EmbeddingModelException("ARK_EMBEDDING_AUTHENTICATION", false),
                AgentFailureCategory.PROVIDER_PERMANENT, false);
    }

    private void assertFailure(EmbeddingModelException providerFailure,
            AgentFailureCategory category, boolean retryable) {
        @SuppressWarnings("unchecked")
        ObjectProvider<KnowledgeRetrievalUseCase> provider = mock(ObjectProvider.class);
        KnowledgeRetrievalUseCase retrieval = mock(KnowledgeRetrievalUseCase.class);
        when(provider.getIfAvailable()).thenReturn(retrieval);
        when(retrieval.searchForAgent(any(), eq("default"), eq(14L), eq("admin")))
                .thenThrow(providerFailure);
        var tool = new KnowledgeSearchAgentTool(provider, new ObjectMapper());
        var request = new AgentTool.Request("default", Map.of("query", "采购规则"),
                Duration.ofSeconds(5), "trace", "key", null, 1, "step", 14L, "admin");

        assertThatThrownBy(() -> tool.execute(request))
                .isInstanceOf(AgentExecutionException.class)
                .satisfies(error -> {
                    var failure = ((AgentExecutionException) error).failure();
                    assertThat(failure.errorCode()).isEqualTo(providerFailure.errorCode());
                    assertThat(failure.category()).isEqualTo(category);
                    assertThat(failure.retryable()).isEqualTo(retryable);
                });
    }
}
