package io.github.illuseahashmap.workflow.process.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentOutputMappingResolverTest {

    private final AgentOutputMappingResolver resolver = new AgentOutputMappingResolver(new ObjectMapper());

    @Test
    void mapsWholeArrayFixedIndexAndProjection() {
        var variables = resolver.resolve(
                "{\"content\":{\"risks\":[{\"level\":\"HIGH\"},{\"level\":\"LOW\"}]}}",
                "{\"risks\":\"allRisks\",\"risks.0.level\":\"firstRisk\","
                        + "\"risks.*.level\":\"riskLevels\"}");

        assertThat(variables.get("allRisks")).isInstanceOf(List.class);
        assertThat(variables.get("firstRisk")).isEqualTo("HIGH");
        assertThat(variables.get("riskLevels")).isEqualTo(List.of("HIGH", "LOW"));
    }

    @Test
    void rejectsProjectionOnNonArray() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.resolve(
                        "{\"content\":{\"risk\":{\"level\":\"HIGH\"}}}",
                        "{\"risk.*.level\":\"riskLevels\"}"))
                .isInstanceOf(AgentCompletionContractException.class)
                .hasMessageContaining("path is missing");
    }
}
