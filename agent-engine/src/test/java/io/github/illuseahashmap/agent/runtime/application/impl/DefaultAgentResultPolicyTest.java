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

    private ModelProviderResponse response(String content, String finishReason) {
        return new ModelProviderResponse(content, "model", "request", finishReason, 1, 1, 0, 10);
    }
}
