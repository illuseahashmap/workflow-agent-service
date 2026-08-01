package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public record ApproveTaskRequest(
        @NotBlank String taskId,
        String currentAssignee,
        List<String> currentCandidateGroups,
        String comment,
        Map<String, Object> variables
) {
}
