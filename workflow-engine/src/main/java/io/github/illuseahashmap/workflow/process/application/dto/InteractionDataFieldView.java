package io.github.illuseahashmap.workflow.process.application.dto;

/** A typed business field required by an Agent at the next automatic frontier. */
public record InteractionDataFieldView(
        String variablePath,
        String label,
        String description,
        String dataType,
        String format,
        boolean required,
        Object currentValue,
        String agentActivityId,
        String agentActivityName,
        String agentInputPath
) {
}
