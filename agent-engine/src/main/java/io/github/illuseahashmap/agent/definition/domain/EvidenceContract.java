package io.github.illuseahashmap.agent.definition.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Published evidence obligations; a model proposal never establishes these facts. */
public record EvidenceContract(
        String subjectField,
        String decisionField,
        String citationsField,
        Set<String> requiredCitations,
        Set<String> abstainDecisions,
        Map<String, Rule> decisions) {
    public record Rule(
            String tool,
            String argumentSubjectField,
            String resultSubjectPointer,
            Map<String, JsonNode> allOf,
            Map<String, JsonNode> anyOf) {}

    public record Observation(String tool, String arguments, String output) {}

    public static EvidenceContract parse(ObjectMapper json, String schema) {
        if (schema == null || schema.isBlank()) {
            return null;
        }
        try {
            var root = json.readTree(schema);
            var n = root.get("x-workflow-evidence");
            if (n == null) {
                return null;
            }
            require(
                    n.isObject()
                            && n.size() == 7
                            && n.path("version").isIntegralNumber()
                            && n.path("version").canConvertToInt()
                            && n.path("version").asInt() == 1);
            String subject = field(n, "subjectField");
            String decision = field(n, "decisionField");
            String cited = field(n, "citationsField");
            require("string".equals(root.path("properties").path(decision).path("type").asText()));
            require(
                    "array".equals(root.path("properties").path(cited).path("type").asText())
                            && "string"
                                    .equals(
                                            root.path("properties")
                                                    .path(cited)
                                                    .path("items")
                                                    .path("type")
                                                    .asText()));
            var citations = new LinkedHashSet<String>();
            require(
                    n.path("requiredCitations").isArray()
                            && !n.path("requiredCitations").isEmpty()
                            && n.path("requiredCitations").size() <= 32);
            for (var c : n.path("requiredCitations")) {
                require(c.isTextual() && !c.asText().isBlank() && c.asText().length() <= 512);
                require(citations.add(c.asText()));
            }
            var rules = new LinkedHashMap<String, Rule>();
            require(n.path("decisions").isObject() && n.path("decisions").size() <= 32);
            var fields = n.path("decisions").fields();
            while (fields.hasNext()) {
                var e = fields.next();
                var r = e.getValue();
                require(!e.getKey().isBlank() && r.isObject() && r.size() == 5);
                String pointer = field(r, "resultSubjectPointer");
                pointer(pointer);
                rules.put(
                        e.getKey(),
                        new Rule(
                                field(r, "tool"),
                                field(r, "argumentSubjectField"),
                                pointer,
                                conditions(r.path("allOf")),
                                conditions(r.path("anyOf"))));
                require(
                        !rules.get(e.getKey()).allOf().isEmpty()
                                || !rules.get(e.getKey()).anyOf().isEmpty());
            }
            var abstentions = new HashSet<String>();
            require(
                    n.path("abstainDecisions").isArray()
                            && n.path("abstainDecisions").size() <= 32);
            for (var value : n.path("abstainDecisions")) {
                require(
                        value.isTextual()
                                && !value.asText().isBlank()
                                && !rules.containsKey(value.asText())
                                && abstentions.add(value.asText()));
            }
            var values = root.path("properties").path(decision).path("enum");
            require(values.isArray() && !values.isEmpty() && values.size() <= 64);
            var declared = new HashSet<String>();
            for (var value : values) {
                require(value.isTextual() && declared.add(value.asText()));
            }
            var covered = new HashSet<>(rules.keySet());
            covered.addAll(abstentions);
            require(declared.equals(covered));
            return new EvidenceContract(
                    subject,
                    decision,
                    cited,
                    Set.copyOf(citations),
                    Set.copyOf(abstentions),
                    Map.copyOf(rules));
        } catch (java.io.IOException e) {
            throw invalid();
        }
    }

    /** Returns false for missing, conflicting, foreign-subject or merely claimed evidence. */
    public boolean supported(
            ObjectMapper json,
            String input,
            String answer,
            Set<String> observedCitations,
            List<Observation> observations) {
        try {
            var out = json.readTree(answer);
            var citations = new HashSet<String>();
            if (out == null || !out.path(citationsField).isArray()) {
                return false;
            }
            for (var c : out.path(citationsField)) {
                if (!c.isTextual() || !observedCitations.contains(c.asText())) {
                    return false;
                }
                citations.add(c.asText());
            }
            if (!citations.containsAll(requiredCitations)) {
                return false;
            }
            var rule = decisions.get(out.path(decisionField).asText());
            if (rule == null) {
                return abstainDecisions.contains(out.path(decisionField).asText());
            }
            var subject = json.readTree(input).path(subjectField);
            if (!subject.isTextual() || subject.asText().isBlank()) {
                return false;
            }
            boolean found = false;
            for (var observation : observations) {
                if (!rule.tool().equals(observation.tool())) {
                    continue;
                }
                var arguments = json.readTree(observation.arguments());
                var result = json.readTree(observation.output());
                if (!subject.equals(arguments.path(rule.argumentSubjectField()))
                        || !subject.equals(result.at(rule.resultSubjectPointer()))) {
                    return false;
                }
                if (!rule.allOf().entrySet().stream()
                                .allMatch(e -> same(e.getValue(), result.at(e.getKey())))
                        || (!rule.anyOf().isEmpty()
                                && rule.anyOf().entrySet().stream()
                                        .noneMatch(
                                                e -> same(e.getValue(), result.at(e.getKey()))))) {
                    return false;
                }
                found = true;
            }
            return found;
        } catch (java.io.IOException | IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }

    /** Bounded projection of original receipts; never model-generated notes. */
    public Map<String, Object> projection(
            ObjectMapper json, List<Observation> observations, Set<String> observedCitations) {
        var records = new java.util.ArrayList<Map<String, Object>>();
        var tools =
                decisions.values().stream()
                        .map(Rule::tool)
                        .collect(java.util.stream.Collectors.toSet());
        for (var o : observations) {
            if (!tools.contains(o.tool())) {
                continue;
            }
            if (records.size() >= 16) {
                break;
            }
            try {
                var result = json.readTree(o.output());
                var values = new LinkedHashMap<String, JsonNode>();
                var args = json.readTree(o.arguments());
                var argumentSubjects = new LinkedHashMap<String, JsonNode>();
                decisions.values().stream()
                        .filter(r -> r.tool().equals(o.tool()))
                        .forEach(
                                r -> {
                                    argumentSubjects.put(
                                            r.argumentSubjectField(),
                                            preview(args.path(r.argumentSubjectField())));
                                    values.put(
                                            r.resultSubjectPointer(),
                                            preview(result.at(r.resultSubjectPointer())));
                                    r.allOf()
                                            .keySet()
                                            .forEach(k -> values.put(k, preview(result.at(k))));
                                    r.anyOf()
                                            .keySet()
                                            .forEach(k -> values.put(k, preview(result.at(k))));
                                });
                records.add(
                        Map.of(
                                "tool",
                                o.tool(),
                                "arguments",
                                argumentSubjects,
                                "observedFields",
                                values));
            } catch (java.io.IOException | NullPointerException malformed) {
                records.add(Map.of("tool", o.tool(), "invalidReceipt", true));
            }
        }
        return Map.of(
                "reviewedCitationsObserved",
                requiredCitations.stream().filter(observedCitations::contains).sorted().toList(),
                "missingReviewedCitations",
                requiredCitations.stream()
                        .filter(c -> !observedCitations.contains(c))
                        .sorted()
                        .toList(),
                "originalReceiptFields",
                records);
    }

    private static JsonNode preview(JsonNode value) {
        if (!value.isValueNode()) {
            return com.fasterxml.jackson.databind.node.TextNode.valueOf("[missing or non-scalar]");
        }
        if (value.isTextual() && value.textValue().length() > 256) {
            return com.fasterxml.jackson.databind.node.TextNode.valueOf(
                    "[oversized scalar; read original receipt]");
        }
        return value;
    }

    private static Map<String, JsonNode> conditions(JsonNode n) {
        require(n.isObject() && n.size() <= 32);
        var result = new LinkedHashMap<String, JsonNode>();
        n.fields()
                .forEachRemaining(
                        e -> {
                            pointer(e.getKey());
                            require(e.getValue().isValueNode() && !e.getValue().isNull());
                            result.put(e.getKey(), e.getValue().deepCopy());
                        });
        return Map.copyOf(result);
    }

    private static boolean same(JsonNode a, JsonNode b) {
        return a.isNumber() && b.isNumber()
                ? a.decimalValue().compareTo(b.decimalValue()) == 0
                : a.equals(b);
    }

    private static void pointer(String p) {
        require(p.startsWith("/") && p.length() <= 256 && !p.matches(".*~(?![01]).*"));
    }

    private static String field(JsonNode n, String key) {
        var v = n.path(key);
        require(v.isTextual() && !v.asText().isBlank() && v.asText().length() <= 256);
        return v.asText();
    }

    private static void require(boolean value) {
        if (!value) {
            throw invalid();
        }
    }

    public static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid version-bound x-workflow-evidence contract");
    }
}
