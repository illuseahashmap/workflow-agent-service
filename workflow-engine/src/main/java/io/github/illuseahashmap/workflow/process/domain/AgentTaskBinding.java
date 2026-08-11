package io.github.illuseahashmap.workflow.process.domain;

import java.util.Objects;

/** Business binding embedded in a BPMN receive-task wait state through workflow-agent extensions. */
public record AgentTaskBinding(
        String taskDefinitionKey,
        String taskName,
        long agentVersionId,
        String inputMappingJson,
        String outputMappingJson,
        String failurePolicy
) {

    public AgentTaskBinding {
        requireText(taskDefinitionKey, "taskDefinitionKey");
        if (agentVersionId <= 0) {
            throw new IllegalArgumentException("agentVersionId must be positive");
        }
        requireText(inputMappingJson, "inputMappingJson");
        requireText(outputMappingJson, "outputMappingJson");
        requireText(failurePolicy, "failurePolicy");
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
