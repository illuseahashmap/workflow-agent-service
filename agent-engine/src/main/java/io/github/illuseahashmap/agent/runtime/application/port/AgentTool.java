package io.github.illuseahashmap.agent.runtime.application.port;

import java.time.Duration;
import java.util.Map;

/** Explicit application boundary for a tenant-authorized Agent tool. */
public interface AgentTool {

    String name();

    default String inputSchema() {
        return "{\"type\":\"object\"}";
    }

    default boolean readOnly() {
        return true;
    }

    Result execute(Request request);

    record Request(String tenantCode, Map<String, Object> arguments, Duration timeout,
                   String traceId, String idempotencyKey, String processInstanceId,
                   long runId, String logicalStepId, Long agentVersionId, String principalId) {

        public Request(String tenantCode, Map<String, Object> arguments, Duration timeout,
                       String traceId, String idempotencyKey, String processInstanceId,
                       long runId, String logicalStepId) {
            this(tenantCode, arguments, timeout, traceId, idempotencyKey, processInstanceId,
                    runId, logicalStepId, null, null);
        }

        public Request withIdempotencyKey(String stableIdempotencyKey) {
            return new Request(tenantCode, arguments, timeout, traceId, stableIdempotencyKey,
                    processInstanceId, runId, logicalStepId, agentVersionId, principalId);
        }
        public Request(String tenantCode, Map<String, Object> arguments, Duration timeout,
                       String traceId, String idempotencyKey, String processInstanceId) {
            this(tenantCode, arguments, timeout, traceId, idempotencyKey, processInstanceId, 0, null, null, null);
        }

        public Request(String tenantCode, Map<String, Object> arguments,
                       Duration timeout, String traceId) {
            this(tenantCode, arguments, timeout, traceId,
                    traceId + ":" + Integer.toHexString(arguments == null ? 0 : arguments.hashCode()), null, 0, null, null, null);
        }

        public Request(String tenantCode, Map<String, Object> arguments,
                       Duration timeout, String traceId, String idempotencyKey) {
            this(tenantCode, arguments, timeout, traceId, idempotencyKey, null, 0, null, null, null);
        }

        public Request(String tenantCode, Map<String, Object> arguments, Duration timeout,
                       String traceId, String processInstanceId, long runId, String logicalStepId) {
            this(tenantCode, arguments, timeout, traceId, null, processInstanceId, runId, logicalStepId, null, null);
        }

        public Request(String tenantCode, Map<String, Object> arguments, Duration timeout,
                       String traceId, String processInstanceId, long runId, String logicalStepId,
                       Long agentVersionId) {
            this(tenantCode, arguments, timeout, traceId, null, processInstanceId, runId, logicalStepId,
                    agentVersionId, null);
        }

        public Request(String tenantCode, Map<String, Object> arguments, Duration timeout,
                       String traceId, String processInstanceId, long runId, String logicalStepId,
                       Long agentVersionId, String principalId) {
            this(tenantCode, arguments, timeout, traceId, null, processInstanceId, runId, logicalStepId,
                    agentVersionId, principalId);
        }
    }

    record Result(String output, String idempotencyKey) {
    }
}
