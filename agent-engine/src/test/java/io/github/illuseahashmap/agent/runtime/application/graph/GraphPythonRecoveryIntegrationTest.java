package io.github.illuseahashmap.agent.runtime.application.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Cross-language probes run in the joint acceptance job with an explicit checkpoint database. */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_LANGGRAPH_JDBC_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "WORKFLOW_LANGGRAPH_CHECKPOINT_DSN", matches = ".+")
class GraphPythonRecoveryIntegrationTest {
    private final GraphPersistenceIntegrationTest fixture = new GraphPersistenceIntegrationTest();

    @BeforeEach
    void initialize() throws Exception {
        fixture.initialize();
    }

    @AfterEach
    void cleanup() {
        fixture.cleanup();
    }

    @Test
    void pythonGraphRecoversLostCandidateAndValidationRepliesWithRealJdbcAuthority()
            throws Exception {
        fixture.pythonGraphRecovery(1);
    }

    @Test
    void pythonV2GraphRecoversLostCandidateAndValidationRepliesWithRealJdbcAuthority()
            throws Exception {
        fixture.pythonGraphRecovery(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void routedGraphRecoversWithRealJdbcAuthority(boolean repair) throws Exception {
        fixture.pythonGraphRecovery(2, true, repair);
    }
}
