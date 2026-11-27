package io.github.illuseahashmap.agent.runtime.application.graph;

import static io.github.illuseahashmap.agent.runtime.application.graph.GraphGatewayContract.require;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.illuseahashmap.agent.runtime.application.port.GraphCognitionPort.ToolFact;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reviewed version-owned checklist; only structured, subject-bound tool receipts can support facts.
 */
public record GraphTaskContract(
        int version, String subjectField, List<FactRule> requiredFacts, boolean allowCorrection) {
    public record FactRule(String id, String tool, String valueField, JsonNode expected) {
        public FactRule {
            field(id);
            field(tool);
            field(valueField);
            require(
                    expected != null
                            && expected.isValueNode()
                            && !expected.isNull()
                            && !expected.isBinary());
            expected = expected.deepCopy();
        }
    }

    public record FactState(
            String id,
            String tool,
            String valueField,
            JsonNode expected,
            String status,
            List<JsonNode> values,
            List<String> receiptRefs) {}

    public record Report(
            String contractDigest,
            String factsDigest,
            String evidenceDigest,
            String subject,
            List<FactState> facts,
            boolean allowCorrection) {
        public boolean accepted() {
            return facts.stream().allMatch(f -> "SATISFIED".equals(f.status()));
        }

        public String reason() {
            if (facts.stream().anyMatch(f -> "CONFLICT".equals(f.status()))) {
                return "GRAPH_FACT_CONFLICT";
            }
            if (facts.stream().anyMatch(f -> "REJECTED".equals(f.status()))) {
                return "GRAPH_FACT_REJECTED";
            }
            return accepted() ? "ACCEPTED" : "GRAPH_FACT_MISSING";
        }
    }

    public GraphTaskContract {
        require(version == 1);
        field(subjectField);
        require(requiredFacts != null && !requiredFacts.isEmpty() && requiredFacts.size() <= 32);
        requiredFacts = List.copyOf(requiredFacts);
        require(
                requiredFacts.stream().map(FactRule::id).distinct().count()
                        == requiredFacts.size());
    }

    private static void field(String field) {
        require(field != null && field.matches("[A-Za-z0-9_.:-]{1,128}"));
    }

    public static GraphTaskContract parseSchema(String schema, GraphGatewayCodec codec) {
        if (schema == null || schema.isBlank()) {
            return null;
        }
        var root = codec.read(schema.getBytes(StandardCharsets.UTF_8), JsonNode.class);
        var contract = root.get("x-graph-checklist");
        return contract == null ? null : codec.read(codec.write(contract), GraphTaskContract.class);
    }

    public Report evaluate(String inputSnapshot, List<ToolFact> receipts, GraphGatewayCodec codec) {
        JsonNode input =
                codec.read(inputSnapshot.getBytes(StandardCharsets.UTF_8), JsonNode.class)
                        .path("input");
        if (input.isTextual()) {
            input = codec.read(input.textValue().getBytes(StandardCharsets.UTF_8), JsonNode.class);
        }
        var subject = input.path(subjectField);
        require(subject.isTextual() && !subject.asText().isBlank());
        var states = new ArrayList<FactState>();
        for (var rule : requiredFacts) {
            var values = new ArrayList<JsonNode>();
            var refs = new ArrayList<String>();
            boolean conflict = false;
            for (var fact :
                    receipts.stream()
                            .sorted(java.util.Comparator.comparing(ToolFact::receiptRef))
                            .toList()) {
                if (!rule.tool().equals(fact.toolCode())) {
                    continue;
                }
                require(GraphGatewayCodec.sha256(fact.output()).equals(fact.outputDigest()));
                var args =
                        codec.read(
                                fact.argumentsJson().getBytes(StandardCharsets.UTF_8),
                                JsonNode.class);
                JsonNode output;
                try {
                    output =
                            codec.read(
                                    fact.output().getBytes(StandardCharsets.UTF_8), JsonNode.class);
                } catch (GraphGatewayException invalid) {
                    conflict = true;
                    refs.add(fact.receiptRef());
                    continue;
                }
                refs.add(fact.receiptRef());
                if (!subject.equals(args.path(subjectField))
                        || !subject.equals(output.path(subjectField))) {
                    conflict = true;
                    continue;
                }
                var value = output.path(rule.valueField());
                if (value.isMissingNode() || value.isNull()) {
                    continue;
                }
                if (!value.isValueNode()) {
                    conflict = true;
                    continue;
                }
                if (values.stream().noneMatch(v -> equivalent(v, value))) {
                    values.add(
                            value.isNumber()
                                    ? com.fasterxml.jackson.databind.node.DecimalNode.valueOf(
                                            value.decimalValue().stripTrailingZeros())
                                    : value);
                }
            }
            values.sort(java.util.Comparator.comparing(codec::fingerprint));
            if (values.size() > 1) {
                conflict = true;
            }
            String status =
                    conflict
                            ? "CONFLICT"
                            : values.isEmpty()
                                    ? "MISSING"
                                    : equivalent(values.getFirst(), rule.expected())
                                            ? "SATISFIED"
                                            : "REJECTED";
            states.add(
                    new FactState(
                            rule.id(),
                            rule.tool(),
                            rule.valueField(),
                            rule.expected(),
                            status,
                            List.copyOf(values),
                            List.copyOf(refs)));
        }
        String digest = codec.fingerprint(this);
        // Re-observing the same fact changes evidence provenance, not the business fact version.
        var facts =
                states.stream()
                        .map(f -> Map.of("id", f.id(), "status", f.status(), "values", f.values()))
                        .toList();
        return new Report(
                digest,
                codec.fingerprint(List.of(digest, subject, facts)),
                codec.fingerprint(states),
                subject.asText(),
                List.copyOf(states),
                allowCorrection);
    }

    private static boolean equivalent(JsonNode a, JsonNode b) {
        return a.isNumber() && b.isNumber()
                ? a.decimalValue().compareTo(b.decimalValue()) == 0
                : a.equals(b);
    }
}
