package io.github.illuseahashmap.workflow.process.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ParticipantAssignment(
        @NotBlank String activityId,
        @NotEmpty @Size(max = 100) List<@NotBlank String> usernames
) {
}
