package io.github.illuseahashmap.agent.runtime.application.port;

import io.github.illuseahashmap.workflow.shared.exception.BusinessException;
import io.github.illuseahashmap.workflow.shared.exception.ErrorCode;

public interface AgentRuntimeBinding extends AgentRunBackendRouter {
    Configuration read(String tenant, long version);

    Configuration configure(String tenant, long version, Configuration configuration, String actor);

    public record Configuration(
            String backend,
            String artifactDigest,
            int stateSchemaVersion,
            int maxModelCalls,
            int maxToolCalls) {
        public static Configuration nativeRuntime() {
            return new Configuration("NATIVE", null, 2, 16, 8);
        }

        public void validate() {
            if (!("NATIVE".equals(backend) || "LANGGRAPH".equals(backend))
                    || stateSchemaVersion != 2
                    || maxModelCalls < 1
                    || maxModelCalls > 100
                    || maxToolCalls < 0
                    || maxToolCalls > 200
                    || ("NATIVE".equals(backend)
                            ? artifactDigest != null
                            : artifactDigest == null || !artifactDigest.matches("[a-f0-9]{64}"))) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid runtime configuration");
            }
        }
    }
}
