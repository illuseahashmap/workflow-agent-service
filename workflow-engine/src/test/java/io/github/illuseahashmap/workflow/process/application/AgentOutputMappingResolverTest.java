package io.github.illuseahashmap.workflow.process.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;

class AgentOutputMappingResolverTest {

    private final AgentOutputMappingResolver resolver =
            new AgentOutputMappingResolver(new ObjectMapper());

    @Test
    void mapsWholeArrayFixedIndexAndProjection() {
        var variables =
                resolver.resolve(
                        "{\"content\":{\"risks\":[{\"level\":\"HIGH\"},{\"level\":\"LOW\"}]}}",
                        "{\"risks\":\"allRisks\",\"risks.0.level\":\"firstRisk\","
                                + "\"risks.*.level\":\"riskLevels\"}");

        assertThat(variables.get("allRisks")).isInstanceOf(List.class);
        assertThat(variables.get("firstRisk")).isEqualTo("HIGH");
        assertThat(variables.get("riskLevels")).isEqualTo(List.of("HIGH", "LOW"));
    }

    @Test
    void rejectsProjectionOnNonArray() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                resolver.resolve(
                                        "{\"content\":{\"risk\":{\"level\":\"HIGH\"}}}",
                                        "{\"risk.*.level\":\"riskLevels\"}"))
                .isInstanceOf(AgentCompletionContractException.class)
                .hasMessageContaining("path is missing");
    }

    @Test
    void preservesExactMoneyInObjectTextAndNestedMappingsWithoutChangingLegacyScalars()
            throws Exception {
        var mapper = new ObjectMapper();
        String content =
                """
                {"amount":20000.000000000001,"small":0.000000000000000000000000000000000000000000000001,
                 "huge":1e400,"ordinary":1.25,"count":7,"approved":false,"empty":null,
                 "items":[{"amount":19999.999999999999}]}
                """;
        String mapping =
                """
                {"amount":"amount","small":"small","huge":"huge","ordinary":"ordinary","count":"count",
                 "approved":"approved","empty":"empty","items":"items","items.*.amount":"amounts"}
                """;
        for (String snapshot :
                List.of(
                        "{\"content\":" + content + "}",
                        mapper.writeValueAsString(java.util.Map.of("content", content)))) {
            var result = resolver.resolve(snapshot, mapping);
            assertThat(result.get("amount"))
                    .isEqualTo(new java.math.BigDecimal("20000.000000000001"));
            assertThat(result.get("huge")).isEqualTo(new java.math.BigDecimal("1e400"));
            assertThat(result.get("small")).isEqualTo(1e-48d);
            assertThat(result.get("ordinary")).isEqualTo(1.25d);
            assertThat(result.get("count")).isEqualTo(7);
            assertThat(result.get("approved")).isEqualTo(false);
            assertThat(result).containsEntry("empty", null);
            assertThat(result.get("amounts"))
                    .isEqualTo(List.of(new java.math.BigDecimal("19999.999999999999")));
            assertThat(result.get("items"))
                    .isEqualTo(
                            List.of(
                                    java.util.Map.of(
                                            "amount",
                                            new java.math.BigDecimal("19999.999999999999"))));
        }
        // Reader configuration is local to mapping, not a mutation of the shared application
        // mapper.
        var shared = new ObjectMapper();
        new AgentOutputMappingResolver(shared)
                .resolve("{\"content\":{\"amount\":1.25}}", "{\"amount\":\"amount\"}");
        assertThat(
                        shared.isEnabled(
                                com.fasterxml.jackson.databind.DeserializationFeature
                                        .USE_BIG_DECIMAL_FOR_FLOATS))
                .isFalse();
    }

    @Test
    void preservesUnderflowAndAnExplicitDecimalMapperPolicy() {
        var configured =
                new ObjectMapper()
                        .enable(
                                com.fasterxml.jackson.databind.DeserializationFeature
                                        .USE_BIG_DECIMAL_FOR_FLOATS);
        var result =
                new AgentOutputMappingResolver(configured)
                        .resolve(
                                "{\"content\":{\"ordinary\":1.25,\"tiny\":1e-400}}",
                                "{\"ordinary\":\"ordinary\",\"tiny\":\"tiny\"}");
        assertThat(result.get("ordinary")).isEqualTo(new java.math.BigDecimal("1.25"));
        assertThat(result.get("tiny")).isEqualTo(new java.math.BigDecimal("1e-400"));
        assertThat(
                        resolver.resolve("{\"content\":{\"tiny\":1e-400}}", "{\"tiny\":\"tiny\"}")
                                .get("tiny"))
                .isEqualTo(new java.math.BigDecimal("1e-400"));
    }
}
