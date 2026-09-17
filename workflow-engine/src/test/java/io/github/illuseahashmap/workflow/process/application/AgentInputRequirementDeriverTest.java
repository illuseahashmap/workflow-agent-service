package io.github.illuseahashmap.workflow.process.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentInputRequirementDeriverTest {

    private final AgentInputRequirementDeriver deriver = new AgentInputRequirementDeriver(new ObjectMapper());

    @Test
    void derivesTypedTenantFacingFieldsFromFrozenAgentBinding() {
        var fields = deriver.derive(
                """
                        {"type":"object","properties":{
                          "customer":{"type":"object","properties":{
                            "name":{"type":"string","title":"客户名称","description":"申请客户"}}},
                          "amount":{"type":"number","title":"申请金额"}}}
                        """,
                """
                        {"customer.name":"${application.customerName}","amount":"approvedAmount"}
                        """,
                Map.of("application", Map.of("customerName", "Acme")),
                "riskAgent",
                "风险分析"
        );

        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).variablePath()).isEqualTo("application.customerName");
        assertThat(fields.get(0).label()).isEqualTo("客户名称");
        assertThat(fields.get(0).currentValue()).isEqualTo("Acme");
        assertThat(fields.get(1).dataType()).isEqualTo("number");
        assertThat(fields.get(1).currentValue()).isNull();
    }

    @Test
    void ignoresLiteralObjectBindingsBecauseTheyDoNotRequireUserInput() {
        var fields = deriver.derive(
                """
                        {"type":"object","properties":{"context":{"type":"object"}}}
                        """,
                """
                        {"context":{"source":"platform"}}
                        """,
                Map.of(),
                "agent",
                "Agent"
        );

        assertThat(fields).isEmpty();
    }

    @Test
    void rejectsInputArrayIndexesAndWildcardsConsistentlyWithDeploymentValidation() {
        String schema = "{\"type\":\"object\",\"properties\":{\"risks\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}";

        assertThatThrownBy(() -> deriver.derive(
                schema, "{\"risks.0\":\"risk\"}", Map.of(), "agent", "Agent"))
                .hasMessageContaining("array indexes or wildcard");
        assertThatThrownBy(() -> deriver.derive(
                schema, "{\"risks.*\":\"risk\"}", Map.of(), "agent", "Agent"))
                .hasMessageContaining("array indexes or wildcard");
    }
}
