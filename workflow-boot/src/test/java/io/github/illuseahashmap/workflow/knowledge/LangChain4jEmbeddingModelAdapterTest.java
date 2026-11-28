package io.github.illuseahashmap.workflow.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import org.junit.jupiter.api.Test;

import java.util.List;

class LangChain4jEmbeddingModelAdapterTest {
    @Test
    void preservesConfiguredVersionDimensionAndInputOrder() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embedAll(anyList()))
                .thenReturn(
                        Response.from(
                                List.of(
                                        Embedding.from(new float[] {1, 0}),
                                        Embedding.from(new float[] {0, 1}))));
        var adapter = new LangChain4jEmbeddingModelAdapter(model, "embedding-model:v1", 2, 2);

        var batch = adapter.embed(List.of("first", "second"));

        assertThat(batch.modelVersion()).isEqualTo("embedding-model:v1");
        assertThat(batch.dimension()).isEqualTo(2);
        assertThat(batch.vectors()).containsExactly(List.of(1f, 0f), List.of(0f, 1f));
    }

    @Test
    void mapsRecoverableFailureButDoesNotHideAuthenticationFailures() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        var adapter = new LangChain4jEmbeddingModelAdapter(model, "embedding-model:v1", 2, 2);
        when(model.embedAll(anyList()))
                .thenThrow(new dev.langchain4j.exception.TimeoutException("timeout"));
        assertThatThrownBy(() -> adapter.embed(List.of("query")))
                .isInstanceOfSatisfying(
                        io.github.illuseahashmap.knowledge.ingestion.application.port
                                .EmbeddingModelException.class,
                        failure -> assertThat(failure.retryable()).isTrue());
        org.mockito.Mockito.doThrow(new dev.langchain4j.exception.AuthenticationException("denied"))
                .when(model)
                .embedAll(anyList());
        assertThatThrownBy(() -> adapter.embed(List.of("query")))
                .isInstanceOf(dev.langchain4j.exception.AuthenticationException.class);
    }

    @Test
    void refusesChangedProviderDimensionBeforeIndexWrite() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embedAll(anyList()))
                .thenReturn(Response.from(List.of(Embedding.from(new float[] {1, 0, 0}))));
        var adapter = new LangChain4jEmbeddingModelAdapter(model, "embedding-model:v1", 2, 2);
        assertThatThrownBy(() -> adapter.embed(List.of("first")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimension");
    }
}
