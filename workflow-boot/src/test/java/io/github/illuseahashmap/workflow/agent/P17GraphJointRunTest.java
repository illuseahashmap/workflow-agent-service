package io.github.illuseahashmap.workflow.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Explicit acceptance job: the same seven gold cases, a real Python graph and Java/Flowable. */
class P17GraphJointRunTest {
    @Test
    @EnabledIfSystemProperty(named = "workflow.p17.graph", matches = "true")
    void graphRunsExercisePublishedBindingsAndOutbox() throws Exception {
        org.assertj.core.api.Assertions.assertThat(
                        System.getenv("WORKFLOW_TEST_DATABASE_URL") != null
                                || org.testcontainers.DockerClientFactory.instance()
                                        .isDockerAvailable())
                .as("Explicit graph acceptance requires Docker or an isolated PostgreSQL server")
                .isTrue();
        new P17JointRunTest().run(false, true);
    }
}
