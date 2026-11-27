package io.github.illuseahashmap.agent.definition.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

class EvidenceContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String SCHEMA =
            """
            {"properties":{"decision":{"type":"string","enum":["READY","MISSING"]},"citations":{"type":"array","items":{"type":"string"}}},
             "x-workflow-evidence":{"version":1,"subjectField":"id","decisionField":"decision","citationsField":"citations",
             "requiredCitations":["reviewed-policy"],"abstainDecisions":["MISSING"],"decisions":{"READY":{"tool":"inventory","argumentSubjectField":"id",
             "resultSubjectPointer":"/subject","allOf":{"/status":"OK","/facts/count":3},"anyOf":{}}}}}
            """;
    private static final String ANSWER =
            "{\"decision\":\"READY\",\"citations\":[\"reviewed-policy\"]}";
    private static final String INPUT = "{\"id\":\"A\"}";

    private EvidenceContract contract() {
        return EvidenceContract.parse(json, SCHEMA);
    }

    private EvidenceContract.Observation receipt(String output) {
        return new EvidenceContract.Observation("inventory", INPUT, output);
    }

    @Test
    void requiresBothReviewedPolicyAndSubjectBoundFacts() {
        var facts =
                List.of(receipt("{\"status\":\"OK\",\"subject\":\"A\",\"facts\":{\"count\":3.0}}"));
        assertThat(contract().supported(json, INPUT, ANSWER, Set.of("reviewed-policy"), facts))
                .isTrue();
        assertThat(contract().supported(json, INPUT, ANSWER, Set.of(), facts)).isFalse();
        assertThat(contract().supported(json, INPUT, ANSWER, Set.of("reviewed-policy"), List.of()))
                .isFalse();
        assertThat(
                        contract()
                                .supported(
                                        json,
                                        INPUT,
                                        ANSWER.replace("reviewed-policy", "invented"),
                                        Set.of("reviewed-policy"),
                                        facts))
                .isFalse();
        assertThat(
                        contract()
                                .supported(
                                        json,
                                        INPUT,
                                        ANSWER.replace("[\"reviewed-policy\"]", "[]"),
                                        Set.of("reviewed-policy"),
                                        facts))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"status\":\"OK\",\"subject\":\"B\",\"facts\":{\"count\":3}}",
                "{\"status\":\"OK\",\"subject\":\"A\",\"facts\":{\"count\":2}}",
                "{\"status\":\"EMPTY\",\"subject\":\"A\",\"facts\":{\"count\":3}}",
                "{\"status\":\"OK\",\"subject\":\"A\"}",
                "invalid"
            })
    void rejectsForeignMissingConflictingAndMalformedReceipts(String bad) {
        assertThat(
                        contract()
                                .supported(
                                        json,
                                        INPUT,
                                        ANSWER,
                                        Set.of("reviewed-policy"),
                                        List.of(receipt(bad))))
                .isFalse();
        assertThat(
                        contract()
                                .supported(
                                        json,
                                        INPUT,
                                        ANSWER,
                                        Set.of("reviewed-policy"),
                                        List.of(
                                                receipt(
                                                        "{\"status\":\"OK\",\"subject\":\"A\",\"facts\":{\"count\":3}}"),
                                                receipt(bad))))
                .isFalse();
    }

    @Test
    void safeAbstentionStillRequiresReviewedPolicyAndDoesNotForgePositiveEvidence() {
        assertThat(
                        contract()
                                .supported(
                                        json,
                                        INPUT,
                                        ANSWER.replace("READY", "MISSING"),
                                        Set.of("reviewed-policy"),
                                        List.of()))
                .isTrue();
        assertThat(EvidenceContract.parse(json, "{}")).isNull();
        assertThat(EvidenceContract.parse(json, null)).isNull();
        assertThat(contract().projection(json, List.of(), Set.of()).get("missingReviewedCitations"))
                .isEqualTo(List.of("reviewed-policy"));
    }

    @Test
    void rejectsMalformedFrozenContract() {
        assertThatThrownBy(
                        () ->
                                EvidenceContract.parse(
                                        json,
                                        SCHEMA.replace("\"version\":1", "\"version\":4294967297")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                EvidenceContract.parse(
                                        json,
                                        SCHEMA.replace(
                                                "\"abstainDecisions\":[\"MISSING\"]",
                                                "\"abstainDecisions\":[]")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                EvidenceContract.parse(
                                        json,
                                        SCHEMA.replace(
                                                "\"abstainDecisions\":[\"MISSING\"]",
                                                "\"abstainDecisions\":[\"MISSING\",\"READY\"]")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                EvidenceContract.parse(
                                        json, SCHEMA.replace("\"version\":1", "\"version\":1.5")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvidenceContract.parse(json, SCHEMA.replace("/subject", "/bad~2")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                EvidenceContract.parse(
                                        json, SCHEMA.replace("[\"reviewed-policy\"]", "[]")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
