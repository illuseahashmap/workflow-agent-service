package io.github.illuseahashmap.agent.runtime.application.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.agent.runtime.application.port.GraphCognitionPort.ToolFact;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphMemoryTest {
    @Test
    void graphSummaryUsesSameEvidenceProjectionAndKeepsLegacyReferenceIndex() throws Exception {
        var codec = new GraphGatewayCodec();
        var memory = new GraphMemory.Context(new GraphMemory.Policy(1, 512, 1024),
                new GraphMemory.Summary("memory:1", "old notes", List.of()), null);
        String original = "{\"metadata\":\"" + "x".repeat(3000)
                + "\",\"evidence\":[{\"text\":\"source body\",\"reference\":{\"locator\":\"citation-1\"}}]}";
        var facts = List.of(new ToolFact("receipt:1", "lookup", "{}", original, GraphGatewayCodec.sha256(original)));
        String input = memory.mergeInput(facts, codec);
        String previews = input.substring(input.indexOf("["));
        var json = new ObjectMapper();
        var view = json.readTree(json.readTree(previews).get(0).path("output_preview").asText());
        assertThat(view.path("evidence").get(0).path("text").asText()).isEqualTo("source body");
        assertThat(view.path("readNext").path("reference").asText()).isEqualTo("receipt:1");
        assertThat(memory.view(facts, codec)).contains("\"reference\":\"receipt:1\"", "\"receiptRef\":\"receipt:1\"");
    }
}
