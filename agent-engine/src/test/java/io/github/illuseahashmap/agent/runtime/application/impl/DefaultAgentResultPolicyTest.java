package io.github.illuseahashmap.agent.runtime.application.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.agent.provider.application.port.ModelProviderResponse;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import org.junit.jupiter.api.Test;

class DefaultAgentResultPolicyTest {

    private final DefaultAgentResultPolicy policy = new DefaultAgentResultPolicy();

    @Test
    void acceptsCompleteNonEmptyResult() {
        var decision = policy.evaluate(null, response("{\"decision\":\"approve\"}", "stop"));

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.status()).isEqualTo(ResultStatus.SUCCESS);
    }

    @Test
    void classifiesEmptyFilteredAndTruncatedResults() {
        assertThat(policy.evaluate(null, response(" ", "stop")).status()).isEqualTo(ResultStatus.EMPTY);
        assertThat(policy.evaluate(null, response("blocked", "content_filter")).status())
                .isEqualTo(ResultStatus.REJECTED);
        assertThat(policy.evaluate(null, response("partial", "length")).status())
                .isEqualTo(ResultStatus.PARTIAL);
    }

    @Test
    void rejectsEvidenceAndBusinessFailuresBeforeFlowableCanAdvance() {
        assertThat(policy.evaluate(null, response("answer", "evidence_insufficient")).status())
                .isEqualTo(ResultStatus.REJECTED);
        assertThat(policy.evaluate(null, response("answer", "business_rejected")).reasonCode())
                .isEqualTo("AGENT_RESULT_BUSINESS_REJECTED");
    }

    @Test
    void honoursExplicitBusinessResultEnvelope() {
        assertThat(policy.evaluate(null, response(
                "{\"resultStatus\":\"PARTIAL\",\"answer\":\"draft\"}", "stop"))
                .reasonCode()).isEqualTo("AGENT_RESULT_PARTIAL");
        assertThat(policy.evaluate(null, response(
                "{\"resultStatus\":\"REJECTED\",\"answer\":\"no\"}", "stop"))
                .status()).isEqualTo(ResultStatus.REJECTED);
    }

    @Test
    void duplicateFieldsCannotOverrideARefusalBeforeJsonRepair() {
        assertThat(policy.responseSignal(response("{\"resultStatus\":\"REJECTED\",\"resultStatus\":\"SUCCESS\"}", "stop"))
                .reasonCode()).isEqualTo("AGENT_RESULT_STATUS_INVALID");
        assertThat(policy.responseSignal(response("{\"items\":[{\"code\":1},{\"code\":2}]}", "stop")).accepted()).isTrue();
    }

    @Test
    void ordinaryMalformedJsonCanStillReachExistingSchemaRepair() {
        assertThat(policy.responseSignal(response("{\"count\":", "stop")).accepted()).isTrue();
        assertThat(policy.responseSignal(response("plain text", "stop")).accepted()).isTrue();
    }

    private ModelProviderResponse response(String content, String finishReason) {
        return new ModelProviderResponse(content, "model", "request", finishReason, 1, 1, 0, 10);
    }
}
