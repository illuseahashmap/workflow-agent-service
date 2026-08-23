package io.github.illuseahashmap.agent.runtime.application.port;

public interface AgentRunOperationsRepository {

    boolean requeueFailed(
            String tenantCode,
            long runId,
            String operatorId,
            String traceId,
            String reason,
            int retryWindowSeconds
    );

    void recordOperation(
            String tenantCode,
            long runId,
            String previousStatus,
            String operatorId,
            String traceId,
            String reason,
            int retryWindowSeconds
    );
}
