package io.github.illuseahashmap.workflow.process.domain;

import java.util.Objects;

/** Business binding embedded in a BPMN receive-task wait state through workflow-agent extensions. */
public record AgentTaskBinding(
        String taskDefinitionKey,
        String taskName,
        long agentVersionId,
        String inputMappingJson,
        String outputMappingJson,
        AgentProcessFailurePolicy processFailurePolicy,
        int processWaitTimeoutSeconds
) {

    public AgentTaskBinding {
        requireText(taskDefinitionKey, "taskDefinitionKey");
        if (agentVersionId <= 0) {
            throw new IllegalArgumentException("agentVersionId must be positive");
        }
        requireText(inputMappingJson, "inputMappingJson");
        requireText(outputMappingJson, "outputMappingJson");
        Objects.requireNonNull(processFailurePolicy, "processFailurePolicy must not be null");
        if (processWaitTimeoutSeconds < 1 || processWaitTimeoutSeconds > 3600) {
            throw new IllegalArgumentException("processWaitTimeoutSeconds must be between 1 and 3600");
        }
    }

    public AgentTaskBinding(
            String taskDefinitionKey, String taskName, long agentVersionId,
            String inputMappingJson, String outputMappingJson, String failurePolicy) {
        this(taskDefinitionKey, taskName, agentVersionId, inputMappingJson, outputMappingJson,
                AgentProcessFailurePolicy.parseCompatible(failurePolicy), 300);
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
