package io.github.illuseahashmap.workflow.process.infrastructure.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.illuseahashmap.workflow.process.domain.AgentProcessFailurePolicy;
import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

class XmlAgentTaskBindingParserTest {

    private final XmlAgentTaskBindingParser parser = new XmlAgentTaskBindingParser(new ObjectMapper());

    @Test
    void parsesTriggerableServiceTaskContract() {
        var binding = parser.parse(bpmn("""
                <bpmn:serviceTask id="agentReview" flowable:async="true" flowable:triggerable="true"
                    flowable:delegateExpression="${agentTaskDelegate}">
                  <bpmn:extensionElements>
                    <workflow:agentTask agentVersionId="42"
                        inputMapping='{"customer":"customer"}'
                        outputMapping='{"decision":"agentDecision"}'
                        processFailurePolicy="MANUAL_REVIEW"
                        processWaitTimeoutSeconds="120" />
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                """)).getFirst();

        assertThat(binding.agentVersionId()).isEqualTo(42L);
        assertThat(binding.processFailurePolicy()).isEqualTo(AgentProcessFailurePolicy.MANUAL_REVIEW);
        assertThat(binding.processWaitTimeoutSeconds()).isEqualTo(120);
    }

    @Test
    void rejectsServiceTaskWithoutTriggerableContract() {
        assertThatThrownBy(() -> parser.parse(bpmn("""
                <bpmn:serviceTask id="agentReview">
                  <bpmn:extensionElements>
                    <workflow:agentTask agentVersionId="42" />
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                """))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("triggerable");
    }

    private String bpmn(String task) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                    xmlns:flowable="http://flowable.org/bpmn"
                    xmlns:workflow="http://workflow-agent.local/bpmn">
                  <bpmn:process id="test" isExecutable="true">
                """ + task + """
                  </bpmn:process>
                </bpmn:definitions>
                """;
    }
}
