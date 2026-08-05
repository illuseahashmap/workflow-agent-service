package io.github.illuseahashmap.workflow.auth.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank
        @Size(min = 8, max = 128)
        String currentPassword,

        @NotBlank
        @Size(min = 8, max = 128)
        String newPassword
) {
}
