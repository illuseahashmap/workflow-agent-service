package io.github.illuseahashmap.workflow.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.knowledge.retrieval.domain.EvidenceLocator;
import java.util.HashSet;
import java.util.Set;

/** Strict boundary mapping keeps Jackson-specific behavior out of the evidence domain. */
public final class EvidenceLocatorCodec {
    private final ObjectMapper mapper;

    public EvidenceLocatorCodec(ObjectMapper mapper) {
        this.mapper = mapper.copy().setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public String encode(EvidenceLocator locator) {
        try {
            return mapper.writeValueAsString(locator);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Evidence locator cannot be serialized", invalid);
        }
    }

    public EvidenceLocator decode(String json) {
        if (json == null || json.length() > 1_000_000) { throw new IllegalArgumentException("Locator exceeds its input budget"); }
        try {
            var node = mapper.readTree(json);
            if (node == null || !node.isObject() || !node.path("kind").isTextual()
                    || !integral(node.get("schemaVersion")) || node.get("schemaVersion").intValue() != 1) {
                throw new IllegalArgumentException("Locator requires an object, integral version 1 and a text kind");
            }
            var kind = EvidenceLocator.Kind.valueOf(node.path("kind").asText());
            Set<String> allowed = new HashSet<>(Set.of("schemaVersion", "kind"));
            if (kind == EvidenceLocator.Kind.TEXT_SECTION) {
                allowed.add("sectionId");
            } else {
                allowed.addAll(Set.of("artifactHash", "elementId", "pageRegions"));
            }
            if (kind == EvidenceLocator.Kind.TABLE_CELLS) { allowed.addAll(Set.of("rowIds", "columnIds")); }
            if (kind == EvidenceLocator.Kind.FIGURE_REGION) { allowed.add("assetHash"); }
            node.fieldNames().forEachRemaining(field -> {
                if (!allowed.contains(field)) { throw new IllegalArgumentException("Field is not allowed for locator kind: " + field); }
            });
            for (String field : allowed) {
                JsonNode value = node.get(field);
                if (value == null || value.isNull()) { throw new IllegalArgumentException("Missing locator field: " + field); }
                switch (field) {
                    case "schemaVersion" -> { /* Validated without coercion above. */ }
                    case "pageRegions" -> validateRegions(value);
                    case "rowIds", "columnIds" -> {
                        if (!value.isArray()) { throw new IllegalArgumentException("Cell selections must be arrays"); }
                        for (JsonNode id : value) { requireText(id); }
                    }
                    default -> requireText(value);
                }
            }
            return mapper.treeToValue(node, EvidenceLocator.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid structured evidence locator", invalid);
        }
    }

    private static boolean integral(JsonNode node) {
        return node != null && node.isNumber() && node.canConvertToExactIntegral() && node.canConvertToInt();
    }

    private static void requireText(JsonNode value) {
        if (!value.isTextual()) { throw new IllegalArgumentException("Locator identifiers must be strings"); }
    }

    private static void validateRegions(JsonNode regions) {
        if (!regions.isArray()) { throw new IllegalArgumentException("Page regions must be an array"); }
        for (JsonNode region : regions) {
            if (!region.isObject() || region.size() != 5 || !integral(region.get("pageIndex"))) {
                throw new IllegalArgumentException("A region requires pageIndex and four coordinates");
            }
            for (String field : Set.of("x0", "y0", "x1", "y1")) {
                if (!region.path(field).isNumber()) { throw new IllegalArgumentException("Coordinates must be numbers"); }
            }
        }
    }
}
