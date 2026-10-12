package io.github.illuseahashmap.agent.runtime.application.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

class CheckpointContextPolicySpringTest {

    @Test
    void createsPolicyThroughSpringWithConfiguredLimits() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "workflow.agent.platform.checkpoint.maximum-context-characters=4096",
                    "workflow.agent.platform.checkpoint.maximum-inline-tool-result-characters=256");
            context.register(CheckpointContextPolicy.class);
            context.refresh();

            CheckpointContextPolicy policy = context.getBean(CheckpointContextPolicy.class);

            assertThat(policy.compact("ready")).isEqualTo("ready");
        }
    }
}
