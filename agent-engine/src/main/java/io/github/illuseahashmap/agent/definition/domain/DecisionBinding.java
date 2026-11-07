package io.github.illuseahashmap.agent.definition.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.LinkedHashMap;

/** Version-owned contract for a workflow discriminator, not a text similarity judgment. */
public record DecisionBinding(String tool, String subjectField, String factField,
                              String outputField, String referenceField, Map<String,String> cases) {
    public static DecisionBinding parse(ObjectMapper mapper, String schema) {
        if (schema == null || schema.isBlank()) return null;
        try {
            JsonNode root = mapper.readTree(schema);
            JsonNode rule = root.get("x-workflow-decision");
            if (rule == null) return null;
            if (!rule.isObject() || (rule.size() != (rule.has("referenceField") ? 7 : 6)) || !"1".equals(field(rule,"version"))) throw invalid();
            String tool = field(rule,"tool"), subject = field(rule,"subjectField"),
                    fact = field(rule,"factField"), output = field(rule,"outputField");
            if (!"string".equals(root.path("properties").path(output).path("type").asText())) throw invalid();
            var cases = new LinkedHashMap<String,String>();
            var mapping = rule.path("cases");
            if (!mapping.isObject() || mapping.isEmpty()) throw invalid();
            mapping.fields().forEachRemaining(e -> cases.put(e.getKey(), field(mapping,e.getKey())));
            String ref = rule.has("referenceField") ? field(rule, "referenceField") : null;
            if (ref != null && (ref.equals(output) || !"string".equals(root.path("properties").path(ref).path("type").asText()))) throw invalid();
            return new DecisionBinding(tool, subject, fact, output, ref, Map.copyOf(cases));
        } catch (java.io.IOException e) { throw invalid(); }
    }
    public static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid version-bound x-workflow-decision contract");
    }
    private static String field(JsonNode n,String key) {
        var value = n.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }
}
