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

    @Test
    void publishedEvidenceCannotBeInventedBySummaryOrRepairedFinalAnswer() throws Exception {
        String schema =
                """
                {"properties":{"decision":{"type":"string","enum":["READY","MISSING"]},"citations":{"type":"array","items":{"type":"string"}}},
                "x-workflow-evidence":{"version":1,"subjectField":"orderId","decisionField":"decision","citationsField":"citations",
                "requiredCitations":["approved"],"abstainDecisions":["MISSING"],"decisions":{"READY":{"tool":"lookup","argumentSubjectField":"orderId",
                "resultSubjectPointer":"/subject","allOf":{"/status":"OK"},"anyOf":{}}}}}
                """;
        String answer = "{\"decision\":\"READY\",\"citations\":[\"approved\"]}";
        var m = memory(schema);
        assertThatThrownBy(() -> m.verify(answer)).isInstanceOf(AgentExecutionException.class);
        String facts = "{\"subject\":\"A\",\"status\":\"OK\"}";
        var receipt =
                new TaskMemory.Receipt(
                        "facts", "f", "lookup", "{\"orderId\":\"A\"}", policy.digest(facts));
        m.observe(receipt, facts);
        assertThatThrownBy(() -> m.verify(answer)).isInstanceOf(AgentExecutionException.class);
        String reference = "{\"sourceType\":\"CHUNK\",\"locator\":\"approved\"}";
        String policyBody =
                "{\"status\":\"SUCCESS\",\"citations\":[{\"reference\":"
                        + reference
                        + "}],\"evidence\":[{\"reference\":"
                        + reference
                        + ",\"text\":\""
                        + "x".repeat(800)
                        + "\"}]}";
        var cited =
                new TaskMemory.Receipt(
                        "policy", "p", "knowledge_search", "{}", policy.digest(policyBody));
        m.observe(cited, policyBody);
        m.merge(
                cited,
                policyBody,
                "model",
                ignored -> {
                    throw new AssertionError("Structured evidence needs no optional model summary");
                });
        m.verify(answer);
        assertThat(m.evidenceProjection())
                .contains("reviewedCitationsObserved", "approved", "originalReceiptFields");
        var restored = memory(schema);
        var state = restored.open(m.snapshot(), 3);
        assertThatThrownBy(() -> restored.verify(answer))
                .isInstanceOf(AgentExecutionException.class);
        for (var r : state.receipts()) {
            restored.observe(r, r.reference().equals("facts") ? facts : policyBody);
        }
        restored.verify(answer);
        assertThatThrownBy(() -> restored.verify(answer.replace("approved", "invented")))
                .isInstanceOf(AgentExecutionException.class);
    }
}
