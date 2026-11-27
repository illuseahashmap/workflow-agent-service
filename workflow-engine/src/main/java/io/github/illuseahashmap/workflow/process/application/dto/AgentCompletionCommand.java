package io.github.illuseahashmap.workflow.process.application.dto;

/** Stable integration contract consumed by the workflow bounded context. */
public record AgentCompletionCommand(
        String tenantCode,
        long runId,
        long attemptId,
        String activityActivationId,
        String traceId
) {
}
