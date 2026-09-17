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

    boolean cancelActive(
            String tenantCode,
            long runId,
            String operatorId,
            String traceId,
            String reason
    );

    default void recordOperation(
            String tenantCode,
            long runId,
            String previousStatus,
            String operatorId,
            String traceId,
            String reason,
            int retryWindowSeconds
    ) {
        recordOperation(tenantCode, runId, "RETRY", previousStatus, "QUEUED", operatorId,
                traceId, reason, retryWindowSeconds);
    }

    void recordOperation(
            String tenantCode,
            long runId,
            String operationType,
            String previousStatus,
            String resultingStatus,
            String operatorId,
            String traceId,
            String reason,
            Integer retryWindowSeconds
    );

    default void recordOperation(
            String tenantCode,
            long runId,
            String operationType,
            String previousStatus,
            String resultingStatus,
            String operatorId,
            String traceId,
            String reason
    ) {
        recordOperation(tenantCode, runId, operationType, previousStatus, resultingStatus,
                operatorId, traceId, reason, null);
    }
}
