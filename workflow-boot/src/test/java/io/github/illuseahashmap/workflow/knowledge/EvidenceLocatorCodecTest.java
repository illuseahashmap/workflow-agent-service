package io.github.illuseahashmap.workflow.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceLocator;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceLocatorCodecTest {
    private final EvidenceLocatorCodec codec = new EvidenceLocatorCodec(new ObjectMapper());

    @Test
    void roundTripsTextWithoutForbiddenNullFieldsAndSupportsCrossPageCells() {
        var text = new EvidenceLocator(1, EvidenceLocator.Kind.TEXT_SECTION, "3.1", null, null,
                List.of(), List.of(), List.of(), null);
        String json = codec.encode(text);
        assertThat(json).doesNotContain("artifactHash", "pageRegions", "rowIds", "null");
        assertThat(codec.decode(json)).isEqualTo(text);
        var table = new EvidenceLocator(1, EvidenceLocator.Kind.TABLE_CELLS, null, "a".repeat(64), "table",
                List.of(new EvidenceLocator.Region(1, 0, 0, 1, 1), new EvidenceLocator.Region(2, 0, 0, 1, 1)),
                List.of("row"), List.of("column"), null);
        assertThat(codec.decode(codec.encode(table))).isEqualTo(table);
    }

    @Test
    void rejectsExtraNullFieldsAndExecutablePathsInsteadOfSilentlyNormalizingThem() {
        assertThatThrownBy(() -> codec.decode("{\"schemaVersion\":1,\"kind\":\"TEXT_SECTION\",\"sectionId\":\"3.1\",\"artifactHash\":null}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("{\"schemaVersion\":1,\"kind\":\"TEXT_SECTION\",\"sectionId\":\"3.1\",\"path\":\"/secret\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingNullAndCoercedPrimitiveFieldsRatherThanInventingLocations() {
        var mapper = new ObjectMapper();
        var pdf = mapper.createObjectNode().put("schemaVersion", 1).put("kind", "PDF_REGION")
                .put("artifactHash", "a".repeat(64)).put("elementId", "element");
        var region = pdf.putArray("pageRegions").addObject().put("pageIndex", 0)
                .put("x0", 0).put("y0", 0).put("x1", 1).put("y1", 1);
        for (String field : List.of("pageIndex", "x0", "y0", "x1", "y1")) {
            for (String invalid : List.of("missing", "null", "string", "boolean")) {
                var changed = pdf.deepCopy();
                var box = (com.fasterxml.jackson.databind.node.ObjectNode) changed.path("pageRegions").get(0);
                switch (invalid) {
                    case "missing" -> box.remove(field);
                    case "null" -> box.putNull(field);
                    case "string" -> box.put(field, "0");
                    default -> box.put(field, true);
                }
                assertThatThrownBy(() -> codec.decode(changed.toString())).isInstanceOf(IllegalArgumentException.class);
            }
        }
        for (String version : List.of("1.9", "\"1\"", "null", "true", "2147483649")) {
            assertThatThrownBy(() -> codec.decode("{\"schemaVersion\":" + version
                    + ",\"kind\":\"TEXT_SECTION\",\"sectionId\":\"3.1\"}"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        region.put("pageIndex", 0.5);
        assertThatThrownBy(() -> codec.decode(pdf.toString())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("null")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("[]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("{\"schemaVersion\":1,\"kind\":\"TEXT_SECTION\",\"sectionId\":42}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode("{\"schemaVersion\":1,\"schemaVersion\":1,\"kind\":\"TEXT_SECTION\",\"sectionId\":\"3.1\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
