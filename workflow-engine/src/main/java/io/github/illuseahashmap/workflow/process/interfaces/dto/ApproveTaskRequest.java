package io.github.illuseahashmap.workflow.process.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

public record ApproveTaskRequest(
        @NotBlank String taskId,
        @NotBlank String currentAssignee,
        List<String> currentCandidateGroups,
        String comment,
        Map<String, Object> variables
) {
}
