package io.github.illuseahashmap.workflow.process.application.dto;

import java.util.List;

/** Generated interaction contract for starting a process or completing a user task. */
public record ProcessInteractionView(
        List<InteractionDataFieldView> fields,
        List<String> agentActivityIds
) {
    public ProcessInteractionView {
        fields = fields == null ? List.of() : List.copyOf(fields);
        agentActivityIds = agentActivityIds == null ? List.of() : List.copyOf(agentActivityIds);
    }
}
