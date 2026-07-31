package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public record RejectTaskRequest(
        @NotBlank String taskId,
        @NotBlank String currentAssignee,
        List<String> currentCandidateGroups,
        @NotBlank String targetActivityId,
        String comment,
        Map<String, Object> variables
) {
}
