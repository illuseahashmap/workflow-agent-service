package io.github.illuseahashmap.agent.runtime.application.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.illuseahashmap.agent.runtime.domain.AgentFailureCategory;
import io.github.illuseahashmap.agent.runtime.domain.AgentFailure;
import io.github.illuseahashmap.agent.runtime.domain.AgentRecoveryAction;
import io.github.illuseahashmap.agent.runtime.domain.ResultStatus;
import org.junit.jupiter.api.Test;

class AgentRecoveryPolicyTest {

    private final AgentRecoveryPolicy policy = new AgentRecoveryPolicy();

    @Test
    void schedulesProviderRetryOnlyWhenBudgetRemains() {
        AgentRecoveryPolicy.Decision decision = policy.decide(
                new AgentFailure("PROVIDER_UNAVAILABLE", AgentFailureCategory.PROVIDER_TRANSIENT,
                        true, ResultStatus.FAILED, "provider unavailable"), true);

        assertThat(decision.failureCategory()).isEqualTo(AgentFailureCategory.PROVIDER_TRANSIENT);
        assertThat(decision.action()).isEqualTo(AgentRecoveryAction.RETRY_PROVIDER);
        assertThat(decision.requiresHumanReview()).isFalse();
    }

    @Test
    void sendsInvalidOutputToHumanReviewAfterRepairBoundary() {
        AgentRecoveryPolicy.Decision decision = policy.decide(
                new AgentFailure("AGENT_OUTPUT_NOT_JSON", AgentFailureCategory.OUTPUT_CONTRACT,
                        false, ResultStatus.FAILED, "invalid output"), false);

        assertThat(decision.failureCategory()).isEqualTo(AgentFailureCategory.OUTPUT_CONTRACT);
        assertThat(decision.action()).isEqualTo(AgentRecoveryAction.WAIT_FOR_REVIEW);
        assertThat(decision.requiresHumanReview()).isTrue();
    }
}
