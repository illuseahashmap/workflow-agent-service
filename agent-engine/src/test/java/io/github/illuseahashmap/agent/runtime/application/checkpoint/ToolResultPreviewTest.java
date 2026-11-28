package io.github.illuseahashmap.agent.runtime.application.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class ToolResultPreviewTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void metadataCannotCrowdOutCompleteEvidenceAndCitationAssociation() throws Exception {
        var root = json.createObjectNode();
        root.put("status", "INSUFFICIENT");
        root.put("abstained", true);
        root.put("metadata", "x".repeat(5000));
        var evidence = root.putArray("evidence");
        for (int i = 0; i < 10; i++) {
            var item = evidence.addObject();
            item.put("text", "body😀".repeat(20));
            item.putObject("reference").put("locator", "chunk-" + i);
        }
        String result = ToolResultPreview.render("receipt-1", root.toString(), 512);
        var view = json.readTree(result);
        assertThat(result.length()).isLessThanOrEqualTo(512);
        assertThat(view.path("complete").asBoolean()).isFalse();
        assertThat(view.path("status").asText()).isEqualTo("INSUFFICIENT");
        assertThat(view.path("abstained").asBoolean()).isTrue();
        assertThat(view.path("evidence").size()).isPositive();
        assertThat(view.path("evidence").get(0)).isEqualTo(evidence.get(0));
        int count = view.path("evidence").size();
        assertThat(view.path("readNext").path("pointer").asText()).isEqualTo("/evidence/" + count);
        assertThat(view.path("readNext").path("reference").asText()).isEqualTo("receipt-1");
        assertThat(view.path("omittedEvidence").asInt()).isEqualTo(10 - count);
    }

    @Test
    void oversizedEvidenceIsExplicitlyExternalAndNeverPresentedAsComplete() throws Exception {
        String body = "{\"evidence\":[{\"text\":\"" + "x".repeat(3000) + "\"}]}";
        var view = json.readTree(ToolResultPreview.render("receipt", body, 256));
        assertThat(view.path("evidence").size()).isZero();
        assertThat(view.path("omittedEvidence").asInt()).isEqualTo(1);
        assertThat(view.path("readNext").path("pointer").asText()).isEqualTo("/evidence/0");
    }

    @Test
    void smallLegacyResultsAreUnchangedAndLargeTextRemainsValidUnicodeJson() throws Exception {
        assertThat(ToolResultPreview.render("receipt", "{\"ok\":true}", 256))
                .isEqualTo("{\"ok\":true}");
        String original = "😀\"\n".repeat(1000);
        String result = ToolResultPreview.render("receipt", original, 256);
        assertThat(result.length()).isLessThanOrEqualTo(256);
        String excerpt = json.readTree(result).path("excerpt").asText();
        assertThat(original).startsWith(excerpt);
        assertThat(Character.isHighSurrogate(excerpt.charAt(excerpt.length() - 1))).isFalse();
    }
}
