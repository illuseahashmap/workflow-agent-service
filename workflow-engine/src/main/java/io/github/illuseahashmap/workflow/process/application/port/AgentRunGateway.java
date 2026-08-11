package io.github.illuseahashmap.workflow.process.application.port;

import io.github.illuseahashmap.workflow.process.application.dto.AgentRunSubmissionResult;

/** Workflow-owned port for starting an asynchronous Agent activity. */
public interface AgentRunGateway {

    AgentRunSubmissionResult submit(AgentRunRequest request);

    record AgentRunRequest(
            String tenantCode,
            long agentVersionId,
            String processInstanceId,
            String executionId,
            String activityId,
            String activityActivationId,
            String inputSnapshotJson,
            String outputMappingJson,
            String processFailurePolicy,
            String idempotencyKey,
            String requestedBy,
            long timeoutSeconds
    ) {
    }
}
