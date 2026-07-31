package io.github.illuseahashmap.workflow.process.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record TransferTaskRequest(
        @NotBlank String taskId,
        @NotBlank String currentAssignee,
        List<String> currentCandidateGroups,
        String targetAssignee,
        List<String> targetCandidateUsers,
        List<String> targetCandidateGroups,
        String comment
) {
}
