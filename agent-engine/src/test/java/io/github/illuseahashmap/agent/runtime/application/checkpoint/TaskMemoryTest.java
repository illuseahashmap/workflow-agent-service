package io.github.illuseahashmap.agent.runtime.application.checkpoint;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.definition.domain.AgentDefinitionVersion;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderException;
import io.github.illuseahashmap.agent.provider.application.port.ModelProviderFailureKind;
import io.github.illuseahashmap.agent.runtime.application.AgentExecutionException;
import io.github.illuseahashmap.agent.runtime.application.port.AgentExecutor;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskMemoryTest {
    final ObjectMapper json = new ObjectMapper();
    final CheckpointContextPolicy policy = new CheckpointContextPolicy(4096, 256);
    private TaskMemory memory(String schema) {
        var version = mock(AgentDefinitionVersion.class);
        when(version.outputSchema()).thenReturn(schema);
        var command = new AgentExecutor.Command("tenant", version, null, "{\"orderId\":\"A\"}", null, "trace");
        var memory = new TaskMemory(command, json, policy);
        memory.route("test", Set.of("lookup"));
        return memory;
    }

    @Test
    void summaryProviderFailureFallsBackWithoutLosingUnicodeSourceOrAssumingAuthorization() throws Exception {
        var memory = memory(null);
        String original = "😀".repeat(400) + "later";
        var r = new TaskMemory.Receipt("ref","call","lookup","{}",policy.digest(original));
        memory.observe(r,original);
        memory.merge(r,original,"model", text -> {
            throw new ModelProviderException("BUSY",ModelProviderFailureKind.RETRYABLE,"test");
        });
        var fold = json.readTree(memory.snapshot()).path("folds").get(0);
        assertThat(fold.path("strategy").asText()).isEqualTo("extractive:v1");
        assertThat(fold.path("inputDigest").asText()).hasSize(64);
        assertThat(json.readTree(memory.readSegment("{\"action\":\"READ_RESULT\",\"reference\":\"ref\",\"offset\":400}"))
                .path("value").asText()).isEqualTo("later");
        assertThatThrownBy(() -> memory.readSegment("{\"action\":\"READ_RESULT\",\"reference\":\"ref\",\"offset\":-1}"))
                .hasMessageContaining("offset");
    }

    @Test
    void missingFactOrMismatchedSubjectIsNotAnExplicitUnknown() {
        String schema = """
            {"properties":{"decision":{"type":"string"}},"x-workflow-decision":{"version":"1","tool":"lookup",
             "subjectField":"orderId","factField":"admission","outputField":"decision","cases":{"UNKNOWN":"VERIFY"}}}
            """;
        var memory = memory(schema);
        for (String body : java.util.List.of("{\"orderId\":\"B\",\"admission\":\"UNKNOWN\"}",
                "{\"orderId\":\"A\"}", "{\"orderId\":\"A\",\"admission\":\"NEW_VALUE\"}")) {
            var r = new TaskMemory.Receipt("ref","call","lookup","{}",policy.digest(body));
            assertThatThrownBy(() -> memory.observe(r,body)).isInstanceOfSatisfying(AgentExecutionException.class,
                    e -> assertThat(e.failure().errorCode()).isEqualTo("AGENT_DECISION_UNVERIFIED"));
        }
        assertThatThrownBy(() -> memory.verify("{\"decision\":\"VERIFY\"}")).hasMessageContaining("authoritative");
    }
}
