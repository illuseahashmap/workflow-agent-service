package io.github.illuseahashmap.agent.runtime.application.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.BooleanNode;

import io.github.illuseahashmap.agent.runtime.application.port.GraphCognitionPort.ToolFact;

import org.junit.jupiter.api.Test;

import java.util.List;

class GraphTaskContractTest {
    private final GraphGatewayCodec codec = new GraphGatewayCodec();
    private final GraphTaskContract contract =
            new GraphTaskContract(
                    1,
                    "subject",
                    List.of(
                            new GraphTaskContract.FactRule(
                                    "approval", "lookup", "approved", BooleanNode.TRUE)),
                    true);

    private ToolFact fact(String ref, String args, String output) {
        return new ToolFact(ref, "lookup", args, output, GraphGatewayCodec.sha256(output));
    }

    private GraphTaskContract.Report report(ToolFact... facts) {
        return contract.evaluate("{\"input\":{\"subject\":\"a\"}}", List.of(facts), codec);
    }

    @Test
    void missingFactsFailClosed() {
        assertThat(report().reason()).isEqualTo("GRAPH_FACT_MISSING");
    }

    @Test
    void positiveSubjectBoundEvidencePasses() {
        assertThat(
                        report(
                                        fact(
                                                "r1",
                                                "{\"subject\":\"a\"}",
                                                "{\"subject\":\"a\",\"approved\":true}"))
                                .accepted())
                .isTrue();
    }

    @Test
    void negativeEvidenceRejects() {
        assertThat(
                        report(
                                        fact(
                                                "r1",
                                                "{\"subject\":\"a\"}",
                                                "{\"subject\":\"a\",\"approved\":false}"))
                                .reason())
                .isEqualTo("GRAPH_FACT_REJECTED");
    }

    @Test
    void foreignArgumentOrOutputCannotSupportSubject() {
        assertThat(
                        report(
                                        fact(
                                                "r1",
                                                "{\"subject\":\"b\"}",
                                                "{\"subject\":\"a\",\"approved\":true}"))
                                .reason())
                .isEqualTo("GRAPH_FACT_CONFLICT");
        assertThat(
                        report(
                                        fact(
                                                "r1",
                                                "{\"subject\":\"a\"}",
                                                "{\"subject\":\"b\",\"approved\":true}"))
                                .reason())
                .isEqualTo("GRAPH_FACT_CONFLICT");
    }

    @Test
    void contradictoryEvidenceCannotBeOverwritten() {
        assertThat(
                        report(
                                        fact(
                                                "r1",
                                                "{\"subject\":\"a\"}",
                                                "{\"subject\":\"a\",\"approved\":false}"),
                                        fact(
                                                "r2",
                                                "{\"subject\":\"a\"}",
                                                "{\"subject\":\"a\",\"approved\":true}"))
                                .reason())
                .isEqualTo("GRAPH_FACT_CONFLICT");
    }

    @Test
    void duplicateFactsChangeEvidenceOnly() {
        var one = fact("r1", "{\"subject\":\"a\"}", "{\"subject\":\"a\",\"approved\":true}");
        var two = fact("r2", one.argumentsJson(), one.output());
        assertThat(report(one).factsDigest()).isEqualTo(report(one, two).factsDigest());
        assertThat(report(one).evidenceDigest()).isNotEqualTo(report(one, two).evidenceDigest());
    }

    @Test
    void malformedOutputAndCorruptDigestFailClosed() {
        assertThat(report(fact("r1", "{\"subject\":\"a\"}", "not json")).reason())
                .isEqualTo("GRAPH_FACT_CONFLICT");
        assertThatThrownBy(() -> report(new ToolFact("r1", "lookup", "{}", "{}", "wrong")))
                .isInstanceOf(GraphGatewayException.class);
    }

    @Test
    void numericFactsPreservePrecisionAndNormalizeEquivalentSpelling() {
        var numeric =
                new GraphTaskContract(
                        1,
                        "subject",
                        List.of(
                                new GraphTaskContract.FactRule(
                                        "amount",
                                        "lookup",
                                        "amount",
                                        com.fasterxml.jackson.databind.node.DecimalNode.valueOf(
                                                new java.math.BigDecimal("20000.000000000001")))),
                        false);
        String input = "{\"input\":{\"subject\":\"a\"}}";
        var exact =
                fact(
                        "r1",
                        "{\"subject\":\"a\"}",
                        "{\"subject\":\"a\",\"amount\":20000.000000000001}");
        var equal =
                fact(
                        "r2",
                        "{\"subject\":\"a\"}",
                        "{\"subject\":\"a\",\"amount\":20000.0000000000010}");
        var rounded = fact("r3", "{\"subject\":\"a\"}", "{\"subject\":\"a\",\"amount\":20000}");
        assertThat(numeric.evaluate(input, List.of(exact), codec).accepted()).isTrue();
        assertThat(numeric.evaluate(input, List.of(exact), codec).factsDigest())
                .isEqualTo(numeric.evaluate(input, List.of(equal, exact), codec).factsDigest());
        assertThat(numeric.evaluate(input, List.of(rounded), codec).reason())
                .isEqualTo("GRAPH_FACT_REJECTED");
    }

    @Test
    void legacySchemaDoesNotOptIn() {
        assertThat(GraphTaskContract.parseSchema("{\"type\":\"object\"}", codec)).isNull();
    }
}
