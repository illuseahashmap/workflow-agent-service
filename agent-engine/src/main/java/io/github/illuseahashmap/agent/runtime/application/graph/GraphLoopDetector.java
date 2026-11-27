package io.github.illuseahashmap.agent.runtime.application.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Deterministic observable repetition, not a semantic assessment of business progress. Policy version 1. */
public final class GraphLoopDetector {
    public record Observation(String action, String result, String receiptRef, int turn, boolean serial) { }
    public record Verdict(String pattern, List<String> evidenceRefs) {
        public boolean stopped() { return !"ALLOW".equals(pattern); }
    }
    private final GraphGatewayCodec codec;
    public GraphLoopDetector(GraphGatewayCodec codec) { this.codec = codec; }

    public String action(String bindingDigest, String tool, String arguments, GraphLoopPolicy policy) {
        return codec.fingerprint(List.of(bindingDigest, tool, canonical(arguments, policy.ignoredArgumentFields().getOrDefault(tool, List.of()), true)));
    }
    public String result(String tool, String output, GraphLoopPolicy policy) {
        return codec.fingerprint(canonical(output, policy.ignoredResultFields().getOrDefault(tool, List.of()), false));
    }
    private Object canonical(String body, List<String> ignored, boolean arguments) {
        JsonNode value;
        try { value = codec.read(body.getBytes(StandardCharsets.UTF_8), JsonNode.class); }
        catch (GraphGatewayException invalid) {
            if (arguments) { throw invalid; }
            return List.of("opaque", GraphGatewayCodec.sha256(body)); // Never interpret invalid/duplicate-key JSON as normalized business facts.
        }
        if (value.isObject()) { ignored.forEach(((com.fasterxml.jackson.databind.node.ObjectNode) value)::remove); }
        return List.of("json", numbers(value));
    }
    private JsonNode numbers(JsonNode node) {
        if (node.isFloatingPointNumber()) { return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros()); }
        if (node.isObject()) {
            var copy = node.deepCopy();
            node.fields().forEachRemaining(e -> ((com.fasterxml.jackson.databind.node.ObjectNode) copy).set(e.getKey(), numbers(e.getValue())));
            return copy;
        }
        if (node.isArray()) {
            var copy = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
            node.forEach(v -> copy.add(numbers(v)));
            return copy;
        }
        return node;
    }

    public Verdict evaluate(String proposed, int turn, boolean serial, List<Observation> history) {
        if (!serial) { return new Verdict("ALLOW", List.of()); }
        var window = new ArrayList<Observation>();
        int lastTurn = -1;
        for (var observation : history) {
            if (observation.turn() >= turn) { continue; } // Never incorporate later results into an old intent's replay.
            if (!observation.serial() || observation.turn() <= lastTurn) { window.clear(); }
            else { window.add(observation); }
            lastTurn = observation.turn();
            if (window.size() > 8) { window.removeFirst(); }
        }
        for (int period = 3; period >= 2; period--) {
            if (window.size() < period * 2) { continue; }
            var tail = window.subList(window.size() - period * 2, window.size());
            boolean equal = proposed.equals(tail.getFirst().action());
            for (int i = 0; i < period && equal; i++) {
                equal = tail.get(i).action().equals(tail.get(i + period).action())
                        && tail.get(i).result().equals(tail.get(i + period).result());
            }
            if (equal) { return new Verdict("SHORT_CYCLE_" + period, tail.stream().map(Observation::receiptRef).toList()); }
        }
        var repeats = window.stream().filter(o -> o.action().equals(proposed)).toList();
        if (repeats.size() >= 2) {
            var last = repeats.subList(repeats.size() - 2, repeats.size());
            if (last.getFirst().result().equals(last.getLast().result())) {
                return new Verdict("REPEATED_ACTION_RESULT", last.stream().map(Observation::receiptRef).toList());
            }
        }
        return new Verdict("ALLOW", window.stream().map(Observation::receiptRef).toList());
    }
}
