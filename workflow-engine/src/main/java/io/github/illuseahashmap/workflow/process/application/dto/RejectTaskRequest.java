package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public record RejectTaskRequest(
        @NotBlank String taskId,
        String currentAssignee,
        List<String> currentCandidateGroups,
        String targetActivityId,
        String comment,
        List<String> targetAssignees,
        List<String> targetCandidateGroups,
        Map<String, Object> variables
) {
}
