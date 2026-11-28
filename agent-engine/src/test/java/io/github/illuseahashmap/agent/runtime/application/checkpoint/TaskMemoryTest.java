package io.github.illuseahashmap.agent.runtime.application.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderFailureKind;
import io.github.illuseahashmap.agent.runtime.application.AgentExecutionException;
import io.github.illuseahashmap.agent.runtime.application.port.AgentExecutor;

import org.junit.jupiter.api.Test;

import java.util.Set;

class TaskMemoryTest {
    final ObjectMapper json = new ObjectMapper();
    final CheckpointContextPolicy policy = new CheckpointContextPolicy(4096, 256);

    private TaskMemory memory(String schema) {
        var version = mock(AgentDefinitionVersion.class);
        when(version.outputSchema()).thenReturn(schema);
        var command =
                new AgentExecutor.Command(
                        "tenant", version, null, "{\"orderId\":\"A\"}", null, "trace");
        var memory = new TaskMemory(command, json, policy);
        memory.route("test", Set.of("lookup"));
        return memory;
    }

    @Test
    void summaryProviderFailureFallsBackWithoutLosingUnicodeSourceOrAssumingAuthorization()
            throws Exception {
        var memory = memory(null);
        String original = "😀".repeat(400) + "later";
        var r = new TaskMemory.Receipt("ref", "call", "lookup", "{}", policy.digest(original));
        memory.observe(r, original);
        memory.merge(
                r,
                original,
                "model",
                text -> {
                    throw new ModelProviderException(
                            "BUSY", ModelProviderFailureKind.RETRYABLE, "test");
                });
        var fold = json.readTree(memory.snapshot()).path("folds").get(0);
        assertThat(fold.path("strategy").asText()).isEqualTo("extractive:v1");
        assertThat(fold.path("inputDigest").asText()).hasSize(64);
        assertThat(
                        json.readTree(
                                        memory.readSegment(
                                                "{\"action\":\"READ_RESULT\",\"reference\":\"ref\",\"offset\":400}"))
                                .path("value")
                                .asText())
                .isEqualTo("later");
        assertThatThrownBy(
                        () ->
                                memory.readSegment(
                                        "{\"action\":\"READ_RESULT\",\"reference\":\"ref\",\"offset\":-1}"))
                .hasMessageContaining("offset");
    }

    @Test
    void receiptAndCitationRemainSeparateAcrossLegacySnapshotRecovery() throws Exception {
        var memory = memory(null);
        String original = "{\"metadata\":\"" + "x".repeat(2000)
                + "\",\"evidence\":[{\"text\":\"authoritative body\",\"reference\":{\"locator\":\"chunk-1\"}}]}";
        var receipt = new TaskMemory.Receipt("receipt-1", "call", "lookup", "{}", policy.digest(original));
        memory.observe(receipt, original);
        memory.merge(receipt, original, "model", input -> {
            assertThat(input).contains("authoritative body", "receipt-1");
            return "notes";
        });
        var restored = memory(null);
        var state = restored.open(memory.snapshot(), 2);
        restored.observe(state.receipts().getFirst(), original);
        assertThat(restored.prompt()).contains("receiptRef", "receipt-1");
        assertThat(json.readTree(restored.readSegment(
                "{\"action\":\"READ_RESULT\",\"reference\":\"receipt-1\",\"pointer\":\"/evidence/0/text\"}"))
                .path("value").asText()).isEqualTo("authoritative body");
        assertThatThrownBy(() -> restored.readSegment(
                "{\"action\":\"READ_RESULT\",\"reference\":\"chunk-1\"}"))
                .isInstanceOfSatisfying(AgentExecutionException.class,
                        e -> assertThat(e.failure().errorCode()).isEqualTo("AGENT_RESULT_READ_INVALID"));
    }

    @Test
    void missingFactOrMismatchedSubjectIsNotAnExplicitUnknown() {
        String schema =
                """
                {"properties":{"decision":{"type":"string"}},"x-workflow-decision":{"version":"1","tool":"lookup",
                 "subjectField":"orderId","factField":"admission","outputField":"decision","cases":{"UNKNOWN":"VERIFY"}}}
                """;
        var memory = memory(schema);
        for (String body :
                java.util.List.of(
                        "{\"orderId\":\"B\",\"admission\":\"UNKNOWN\"}",
                        "{\"orderId\":\"A\"}",
                        "{\"orderId\":\"A\",\"admission\":\"NEW_VALUE\"}")) {
            var r = new TaskMemory.Receipt("ref", "call", "lookup", "{}", policy.digest(body));
            assertThatThrownBy(() -> memory.observe(r, body))
                    .isInstanceOfSatisfying(
                            AgentExecutionException.class,
                            e ->
                                    assertThat(e.failure().errorCode())
                                            .isEqualTo("AGENT_DECISION_UNVERIFIED"));
        }
        assertThatThrownBy(() -> memory.verify("{\"decision\":\"VERIFY\"}"))
                .hasMessageContaining("authoritative");
    }
}
