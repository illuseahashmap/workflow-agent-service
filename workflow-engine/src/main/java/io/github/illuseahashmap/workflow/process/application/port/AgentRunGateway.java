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
            String nodeToolSetJson,
            String idempotencyKey,
            String requestedBy,
            long timeoutSeconds
    ) {

        /**
         * Compatibility constructor for callers created before BPMN node tool
         * binding became part of the workflow-to-agent contract.
         */
        public AgentRunRequest(
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
            this(tenantCode, agentVersionId, processInstanceId, executionId, activityId,
                    activityActivationId, inputSnapshotJson, outputMappingJson,
                    processFailurePolicy, null, idempotencyKey, requestedBy, timeoutSeconds);
        }
    }
}
