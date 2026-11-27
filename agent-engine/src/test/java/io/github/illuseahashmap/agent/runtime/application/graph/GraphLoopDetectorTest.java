package io.github.illuseahashmap.agent.runtime.application.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class GraphLoopDetectorTest {
    private final GraphLoopDetector detector = new GraphLoopDetector(new GraphGatewayCodec());
    private final GraphLoopPolicy policy = GraphLoopPolicy.defaults();

    private List<GraphLoopDetector.Observation> sequence(String sequence) {
        var result = new ArrayList<GraphLoopDetector.Observation>();
        for (int i = 0; i < sequence.length(); i++) {
            String action = sequence.substring(i, i + 1);
            result.add(
                    new GraphLoopDetector.Observation(
                            action, "result:" + action, "receipt:" + i, i + 1, true));
        }
        return result;
    }

    @Test
    void aaAndShortCyclesAreDetectedBeforeAnotherProposal() {
        assertThat(detector.evaluate("A", 3, true, sequence("AA")).pattern())
                .isEqualTo("REPEATED_ACTION_RESULT");
        assertThat(detector.evaluate("A", 5, true, sequence("ABAB")).pattern())
                .isEqualTo("SHORT_CYCLE_2");
        assertThat(detector.evaluate("A", 7, true, sequence("ABCABC")).pattern())
                .isEqualTo("SHORT_CYCLE_3");
        assertThat(detector.evaluate("D", 7, true, sequence("ABCABC")).stopped()).isFalse();
    }

    @Test
    void changedResultsAndNewPagesAreNotProvenRepetition() {
        var history = new ArrayList<>(sequence("AA"));
        history.set(
                1,
                new GraphLoopDetector.Observation("A", "new-result", "receipt:changed", 2, true));
        assertThat(detector.evaluate("A", 3, true, history).stopped()).isFalse();
        assertThat(detector.evaluate("G", 7, true, sequence("ABCDEF")).stopped()).isFalse();
    }

    @Test
    void independentSiblingsCannotBeLinearizedIntoACycle() {
        var history = new ArrayList<>(sequence("AA"));
        history.add(new GraphLoopDetector.Observation("A", "result:A", "batch:1", 3, false));
        assertThat(detector.evaluate("A", 4, true, history).stopped()).isFalse();
        assertThat(detector.evaluate("A", 3, false, sequence("AA")).stopped()).isFalse();
    }

    @Test
    void oldIntentDoesNotReadFutureFactsAndWindowIsBounded() {
        assertThat(detector.evaluate("A", 2, true, sequence("AAAA")).stopped()).isFalse();
        assertThat(detector.evaluate("A", 12, true, sequence("AABCDEFGHIJ")).stopped()).isFalse();
    }

    @Test
    void jsonKeyOrderAndDecimalSpellingDoNotCreateNewActions() {
        assertThat(
                        detector.action(
                                "binding", "tool", "{\"b\":1.00,\"a\":{\"y\":2,\"x\":3}}", policy))
                .isEqualTo(
                        detector.action(
                                "binding", "tool", "{\"a\":{\"x\":3,\"y\":2},\"b\":1}", policy));
        assertThat(detector.result("tool", "{\"b\":2,\"a\":1}", policy))
                .isEqualTo(detector.result("tool", "{\"a\":1,\"b\":2}", policy));
    }

    @Test
    void arrayOrderAmountsSubjectsAndToolsStaySemantic() {
        assertThat(action("{\"items\":[1,2]}")).isNotEqualTo(action("{\"items\":[2,1]}"));
        assertThat(action("{\"amount\":20000.000000000001}"))
                .isNotEqualTo(action("{\"amount\":20000}"));
        assertThat(action("{\"order\":\"P17\"}")).isNotEqualTo(action("{\"order\":\"P18\"}"));
        assertThat(detector.action("binding", "tool", "{}", policy))
                .isNotEqualTo(detector.action("other-binding", "tool", "{}", policy));
        assertThat(detector.action("binding", "tool", "{}", policy))
                .isNotEqualTo(detector.action("binding", "other-tool", "{}", policy));
    }

    @Test
    void onlyExplicitFrozenMetadataDeclarationsIgnoreTraceFields() {
        var declared =
                new GraphLoopPolicy(
                        Map.of("tool", List.of("traceId")), Map.of("tool", List.of("request_id")));
        String a = "{\"query\":\"a\",\"traceId\":\"one\"}";
String b = "{\"traceId\":\"two\",\"query\":\"a\"}";
        assertThat(action(a)).isNotEqualTo(action(b));
        assertThat(detector.action("binding", "tool", a, declared))
                .isEqualTo(detector.action("binding", "tool", b, declared));
        assertThat(detector.result("tool", "{\"items\":[],\"request_id\":\"one\"}", declared))
                .isEqualTo(
                        detector.result("tool", "{\"request_id\":\"two\",\"items\":[]}", declared));
        assertThatThrownBy(() -> new GraphLoopPolicy(Map.of("tool", List.of("amount")), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repeatedEmptyOrOpaqueOutputsAreStableWithoutInventingFacts() {
        assertThat(detector.result("tool", "[]", policy))
                .isEqualTo(detector.result("tool", "[ ]", policy));
        assertThat(detector.result("tool", "no data", policy))
                .isEqualTo(detector.result("tool", "no data", policy));
        assertThat(detector.result("tool", "{\"x\":1,\"x\":2}", policy))
                .isNotEqualTo(detector.result("tool", "{\"x\":2}", policy));
    }

    private String action(String value) {
        return detector.action("binding", "tool", value, policy);
    }
}
