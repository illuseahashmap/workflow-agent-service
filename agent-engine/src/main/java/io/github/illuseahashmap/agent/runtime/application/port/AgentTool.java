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
                   String traceId, String idempotencyKey) {
        public Request(String tenantCode, Map<String, Object> arguments,
                       Duration timeout, String traceId) {
            this(tenantCode, arguments, timeout, traceId,
                    traceId + ":" + Integer.toHexString(arguments == null ? 0 : arguments.hashCode()));
        }
    }

    record Result(String output, String idempotencyKey) {
    }
}
