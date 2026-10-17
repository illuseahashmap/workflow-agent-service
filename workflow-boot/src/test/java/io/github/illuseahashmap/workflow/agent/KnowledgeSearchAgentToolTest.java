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
