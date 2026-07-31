package io.github.illuseahashmap.workflow.process.model;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record StartProcessRequest(
        @NotBlank String processDefinitionKey,
        String processDefinitionId,
        String businessKey,
        Map<String, Object> variables
) {
}
